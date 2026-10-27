package io.github.retronym.sharing

import scala.collection.mutable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Assert.*
import org.junit.Test

/** What `Sharing`'s operations promise in a concurrent mode, one section per operation, each checked by Lincheck.
 *
 *  How to read it: a section says in prose what the operation promises, then gives
 *
 *  - a specification: a plain class that does what the promise says, as one thread would see it, and
 *  - a class whose operations, of the same names, use the real operation from several threads.
 *
 *  Lincheck calls the operations from several threads at once and fails if their results are not ones that some
 *  sequential order of the same calls gives on the specification (linearizability). The operations are written so
 *  that breaking a promise shows in a result: a number returned twice, a cycle where there is none, a `false`.
 *
 *  A claim is taken from the `ClaimRegistry` given, and each section runs on two that keep the registry's contract
 *  (one holder per key, re-entry by the holder runs at once), the two shapes dotc's take:
 *
 *  - `OneLock`, one reentrant lock for every key whose kind says serialised threads take it, as the completion lock;
 *  - `LockPerKey`, a reentrant lock per key, as the completion roots, without their wait-for cycle check (no
 *    section claims keys in opposite orders; `SharingOnCompletionRootsLincheckTest` runs these sections, and one that
 *    does, on dotc's registry).
 *
 *  Each is checked two ways: model checking explores the interleavings of a scenario under sequential consistency
 *  (it finds orderings, not missing fences); stress runs real threads (on Apple silicon, so weak memory too).
 *
 *  Bounds, so the suite takes seconds: per section and registry, 3 threads of 3 calls; model checking 20 scenarios
 *  of 500 interleavings each, stress 10 scenarios of 1000 runs each.
 */
class SharingLincheckTest:
  import SharingLincheckTest.*

  @Test def claim(): Unit = checkEveryWay(classOf[ClaimSpec])(classOf[OneLockClaim], classOf[LockPerKeyClaim])
  @Test def once(): Unit = checkEveryWay(classOf[OnceSpec])(classOf[OneLockOnce], classOf[LockPerKeyOnce])
  @Test def onceCatchesAFactLeftStartedByAFailure(): Unit = assertFails(classOf[OnceSpec])(classOf[OnceLeftStarted])
  @Test def blackHole(): Unit = checkEveryWay(classOf[BlackHoleSpec])(classOf[OneLockBlackHole], classOf[LockPerKeyBlackHole])
  @Test def guard(): Unit = checkEveryWay(classOf[GuardSpec])(classOf[ConcurrentGuard])

object SharingLincheckTest:

  given Mode = Mode.Concurrent

  // ---- claim(key)(op): one writer per key

  /** At most one thread runs inside the claims on a key at a time, whether taken by `claim` or by `claimAt`; the
   *  holder of a claim that claims the same key again runs at once, it does not wait for itself.
   *
   *  Each operation counts inside the claim on `key`, with a counter that reads, then stores one more: two threads
   *  inside at once can return the same number.
   */
  class ClaimSpec extends CounterSpec:
    def claim(): Int = next()
    def claimAt(): Int = next()
    def reenter(): Int = next()
    def nested(): Int = next()

  abstract class Claim(using ClaimRegistry):
    private val key, other = Key()
    private val counter = Counter()

    @Operation def claim(): Int = Sharing.claim(key)(counter.count())
    @Operation def claimAt(): Int = Sharing.claimAt(key, Key.kind, key)(counter.count())
    @Operation def reenter(): Int = Sharing.claim(key)(Sharing.claim(key)(counter.count()))
    @Operation def nested(): Int = Sharing.claim(other)(Sharing.claim(key)(counter.count()))

  class OneLockClaim extends Claim(using OneLock())
  class LockPerKeyClaim extends Claim(using LockPerKey())

  // ---- once(key)(peek)(compute): a lazy fact

  /** A lazy fact is computed at most once, and every reader sees it whole once it is there; a computation that fails
   *  leaves the fact as it found it, for the next reader to compute.
   *
   *  - `get` returns the fact, 42.
   *  - `getFailing` computes it with a computation that throws, -1, unless it is there already.
   *  - `computations` is how many times the fact was stored: 0, then 1.
   */
  class OnceSpec:
    private var there = false
    def get(): Int = { there = true; 42 }
    def getFailing(): Int = if there then 42 else -1
    def computations(): Int = if there then 1 else 0

  /** The fact is stored when its computation ends and peeked through what was stored, so its field reads 0 if it was
   *  not published whole. The computation runs in the fact's black hole (`computeInside`): finding it entered is a
   *  cycle, which only the holder of the claim, re-entering its own computation, could see, and which is reported as
   *  -2, a result the specification never gives.
   */
  abstract class Once(using ClaimRegistry) extends Sharing.BlackHole, Lockable:
    private var value: Fact | Null = null
    private val stored = AtomicInteger()

    @Operation def get(): Int = read(fail = false)
    @Operation def getFailing(): Int = read(fail = true)
    @Operation def computations(): Int = stored.get

    private def read(fail: Boolean): Int =
      try Sharing.once(this)(value)(guardedCompute(fail)).n
      catch
        case _: Failure => -1
        case _: FalseCycle => -2

    /** The computation, guarded against re-entry. */
    protected def guardedCompute(fail: Boolean)(using Sharing.Holds[this.type]): Fact

    protected def compute(fail: Boolean)(using Sharing.Holds[this.type]): Fact =
      if fail then throw Failure()
      val f = Fact(42)
      stored.incrementAndGet()
      value = f
      f

  abstract class BlackHoleOnce(using ClaimRegistry) extends Once:
    protected def guardedCompute(fail: Boolean)(using Sharing.Holds[this.type]): Fact =
      computeInside(throw FalseCycle())(compute(fail))

  given Sharing.ClaimKind[Once] = Key.kind

  class OneLockOnce extends BlackHoleOnce(using OneLock())
  class LockPerKeyOnce extends BlackHoleOnce(using LockPerKey())

  /** `LazyRef` before I97: a "started" flag, set as the computation begins, that a computation which unwound left
   *  set, so the next reader reported a cycle. */
  class OnceLeftStarted extends Once(using LockPerKey()):
    private var started = false
    protected def guardedCompute(fail: Boolean)(using Sharing.Holds[this.type]): Fact =
      if started then throw FalseCycle()
      started = true
      compute(fail)

  final class Fact(val n: Int)
  private final class Failure extends RuntimeException
  private final class FalseCycle extends RuntimeException

  // ---- BlackHole: which thread is inside a computation

  /** Under the claim, before it enters, no thread is inside; inside, this thread is, and still is after a nested
   *  entry and its exit; after the exit, no thread is. Outside, this thread is not inside, whoever else is.
   *  Each check is a `Boolean`; the specification says every one holds.
   */
  class BlackHoleSpec:
    def enter(): Boolean = true
    def observe(): Boolean = true

  abstract class BlackHole(using ClaimRegistry) extends Sharing.BlackHole, Lockable:
    @Operation def enter(): Boolean = Sharing.claim(this):
      val before = nobodyInside
      val within = inside:
        val outer = meInside
        val nested = inside(meInside)
        outer && nested && meInside
      before && within && nobodyInside

    @Operation def observe(): Boolean = !isInsideHere

    private def meInside(using Sharing.Holds[this.type]) = isInside && isInsideHere
    private def nobodyInside(using Sharing.Holds[this.type]) = !isInside && !isInsideHere

  given Sharing.ClaimKind[BlackHole] = Key.kind

  class OneLockBlackHole extends BlackHole(using OneLock())
  class LockPerKeyBlackHole extends BlackHole(using LockPerKey())

  // ---- Guard: state reached only under a monitor

  /** A guard's variable and collection, reached in `locked` (and `alwaysLocked`, in every mode), behave as a
   *  variable and a collection that one thread uses. A guard takes no claim, so no registry.
   */
  class GuardSpec extends CounterSpec:
    private val items = mutable.ArrayBuffer.empty[Int]
    def count(): Int = next()
    def add(): Unit = items += 1
    def addAlways(): Unit = items += 2
    def snapshot(): List[Int] = items.toList

  class ConcurrentGuard extends Sharing.Guard:
    private val counter = guardedVar(0)
    private val items = guarded(mutable.ArrayBuffer.empty[Int])

    @Operation def count(): Int = locked:
      val n = counter()
      counter() = n + 1
      n
    @Operation def add(): Unit = locked(items() += 1)
    @Operation def addAlways(): Unit = alwaysLocked(items() += 2)
    @Operation def snapshot(): List[Int] = locked(items().toList)

  // ---- what the sections share

  /** A key claimed as a completion's root is: serialised threads take the coarse lock for it. A key whose kind says
   *  they need not (a lazy reference) relies on the coarse lock its caller holds already. */
  final class Key extends Lockable
  object Key:
    val kind: Sharing.ClaimKind[Any] = Sharing.ClaimKind(What("test", null), lockWhenSerialised = true)
    given Sharing.ClaimKind[Key] = kind

  /** A counter that is right only if one thread at a time counts: it reads, then stores one more. */
  final class Counter:
    private var n = 0
    def count(): Int =
      val v = n
      n = v + 1
      v

  /** A specification that numbers its calls. */
  abstract class CounterSpec:
    private var n = 0
    protected def next(): Int =
      n += 1
      n - 1

  /** `op`, restarted while it is cancelled (backs off from a wait-for cycle), as a request is. */
  @annotation.tailrec
  def restarting[T](op: => T): T =
    val result = try Some(op) catch case _: Cancelled => None
    result match
      case Some(t) => t
      case None => restarting(op)

  // ---- the registries

  /** One reentrant lock, taken for a key whose kind says serialised threads take it: dotc's completion lock. */
  final class OneLock extends ClaimRegistry:
    private val lock = ReentrantLock()
    def claim[T](key: AnyRef, kind: Sharing.ClaimKind[Nothing], cycleSubject: AnyRef, op: () => T): T =
      if kind.lockWhenSerialised then
        lock.lock()
        try op() finally lock.unlock()
      else op()

  /** A reentrant lock per key: dotc's completion roots, without the wait-for cycle check. The lock is the key's own
   *  (a `Lockable`), not found in a map, so that a claim is one lock under model checking (a map walk on every claim
   *  makes a long operation, which Lincheck reads as a hang).
   */
  final class LockPerKey extends ClaimRegistry:
    def claim[T](key: AnyRef, kind: Sharing.ClaimKind[Nothing], cycleSubject: AnyRef, op: () => T): T =
      val lock = key.asInstanceOf[Lockable].lock
      lock.lock()
      try op() finally lock.unlock()

  /** A key claimed through `LockPerKey`. */
  trait Lockable:
    final val lock: ReentrantLock = ReentrantLock()

  // ---- the checks

  private def modelCheck(spec: Class[?], cls: Class[?]): Unit =
    LinChecker.check(cls, ModelCheckingOptions().sequentialSpecification(spec)
      .iterations(20).invocationsPerIteration(500).threads(3).actorsPerThread(3))

  def stress(spec: Class[?], cls: Class[?], scenarios: Int = 10, runs: Int = 1000): Unit =
    LinChecker.check(cls, StressOptions().sequentialSpecification(spec)
      .iterations(scenarios).invocationsPerIteration(runs).threads(3).actorsPerThread(3))

  def checkEveryWay(spec: Class[?])(classes: Class[?]*): Unit =
    for cls <- classes do
      modelCheck(spec, cls)
      stress(spec, cls)

  def assertFails(spec: Class[?])(cls: Class[?]): Unit =
    val failed =
      try { modelCheck(spec, cls); false }
      catch case _: AssertionError => true
    assertTrue(s"${cls.getSimpleName} should not meet ${spec.getSimpleName}", failed)
