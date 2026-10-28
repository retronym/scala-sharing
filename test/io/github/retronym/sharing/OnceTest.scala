package io.github.retronym.sharing

import java.util.concurrent.atomic.AtomicInteger

import scala.compiletime.testing.typeCheckErrors

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Assert.*
import org.junit.Test

/** `Once` fields: computed once in each mode, re-entry reaching `compute` with the claim's evidence, a plain access
 *  and a key other than the owner not type-checking, and, under Lincheck, one computation however readers interleave.
 */
class OnceTest:
  import OnceTest.*

  @Test def batch(): Unit =
    given Mode = Mode.Batch
    given ClaimRegistry = Concurrency.Serialised.newRegistry(() => Claimant())
    val c = Cell()
    assertNull(c.info.peek)
    assertEquals("info 1", c.get)
    assertEquals("info 1", c.get)
    assertEquals("info 1", c.info.peek)
    c.invalidate()
    assertEquals("info 2", c.get)

  @Test def concurrent(): Unit =
    given Mode = Mode.Concurrent
    given ClaimRegistry = Concurrency.Parallel.newRegistry(claimantOfThisThread)
    val c = Cell()
    assertEquals("info 1", c.get)
    assertSame(c.get, c.get)
    c.invalidate()
    assertEquals("info 2", c.get)

  @Test def setUnderTheClaim(): Unit =
    for (m, r) <- List(Mode.Batch -> Concurrency.Serialised.newRegistry(() => Claimant()),
                       Mode.Concurrent -> Concurrency.Parallel.newRegistry(claimantOfThisThread)) do
      given Mode = m
      given ClaimRegistry = r
      val c = Cell()
      c.put("given")
      assertEquals("given", c.info.acquire)
      assertEquals("given", c.get) // found: not computed
      c.invalidate()
      assertNull(c.info.acquire)
      assertEquals("info 1", c.get)

  @Test def reentryIsTheOwnersCycle(): Unit =
    given Mode = Mode.Concurrent
    given ClaimRegistry = Concurrency.Parallel.newRegistry(claimantOfThisThread)
    val c = Cyclic()
    assertThrows(classOf[CycleSeen], () => c.get)
    assertNull(c.f.peek) // unwound: computable again

  @Test def misuseDoesNotCompile(): Unit =
    // a plain read or store, a store with no claim
    assertFalse(typeCheckErrors("(Cell()(using Mode.Batch, null).info: String | Null)").isEmpty)
    assertFalse(typeCheckErrors("{ val c = Cell()(using Mode.Batch, null); c.info = null }").isEmpty)
    assertFalse(typeCheckErrors("{ given Mode = Mode.Batch; Cell()(using Mode.Batch, null).info.reset() }").isEmpty)
    assertFalse(typeCheckErrors("{ given Mode = Mode.Batch; Cell()(using Mode.Batch, null).info.set(\"x\") }").isEmpty)
    // keyed by its class: not a singleton, so no claim key
    assertFalse(typeCheckErrors("{ given Mode = Mode.Batch; given ClaimRegistry = null; ByClass().f.get(\"x\") }").isEmpty)
    // keyed by another object than its owner
    val errs = typeCheckErrors(
      "{ given Mode = Mode.Batch; given ClaimRegistry = null; val k = ByOther(); k.f.get(\"x\") }")
    assertTrue(errs.map(_.message).mkString, errs.exists(_.message.contains("not by its owner")))

  @Test def lincheckModelChecking(): Unit =
    LinChecker.check(classOf[Racing], ModelCheckingOptions().iterations(100).threads(3).actorsPerThread(3))

  @Test def lincheckStress(): Unit =
    LinChecker.check(classOf[Racing], StressOptions().iterations(100).invocationsPerIteration(5000).threads(3).actorsPerThread(3))

object OnceTest:
  private val claimants = ThreadLocal.withInitial(() => Claimant())
  val claimantOfThisThread: () => Claimant = () => claimants.get.nn

  final class CycleSeen extends RuntimeException

  final class Cell(using Mode, ClaimRegistry):
    private[sharing] var info: Once[this.type, String] = Once.empty
    private var computed = 0
    def get: String = info.get { computed += 1; s"info $computed" }
    def invalidate(): Unit = Sharing.claim(this)(info.reset())
    def put(s: String): Unit = Sharing.claim(this)(info.set(s))

  object Cell:
    given Sharing.ClaimKind[Cell] = Sharing.ClaimKind(What("computing info", null), lockWhenSerialised = true)
    val infoHandle: Published.Handle[Cell, "info"] = Published.handle
    given Published.Handle[Cell, "info"] = infoHandle

  final class Cyclic(using Mode, ClaimRegistry) extends Sharing.BlackHole:
    private[sharing] var f: Once[this.type, String] = Once.empty
    def get: String = f.get(computeInside(throw CycleSeen())(get))

  object Cyclic:
    given Sharing.ClaimKind[Cyclic] = Sharing.ClaimKind(What("cyclic", null), lockWhenSerialised = true)
    val fHandle: Published.Handle[Cyclic, "f"] = Published.handle
    given Published.Handle[Cyclic, "f"] = fHandle

  final class ByClass:
    private[sharing] var f: Once[ByClass, String] = Once.empty

  object ByClass:
    given Sharing.ClaimKind[ByClass] = Sharing.ClaimKind(What("by class", null), lockWhenSerialised = true)
    val fHandle: Published.Handle[ByClass, "f"] = Published.handle
    given Published.Handle[ByClass, "f"] = fHandle

  final class ByOther:
    val other: AnyRef = new Object
    private[sharing] var f: Once[other.type, String] = Once.empty

  object ByOther:
    given Sharing.ClaimKind[AnyRef] = Sharing.ClaimKind(What("by other", null), lockWhenSerialised = true)
    val fHandle: Published.Handle[ByOther, "f"] = Published.handle
    given Published.Handle[ByOther, "f"] = fHandle

  /** Readers race to `get` a fact whose computation throws if it runs twice: every `get` returns the one value, and
   *  `peek` that value or null. */
  final class Racing:
    private given Mode = Mode.Concurrent
    private given ClaimRegistry = Concurrency.Parallel.newRegistry(claimantOfThisThread)
    private[sharing] var fact: Once[this.type, String] = Once.empty
    private val computations = AtomicInteger()

    @Operation def get(): String = fact.get:
      if computations.incrementAndGet() != 1 then throw IllegalStateException("computed twice")
      "fact"
    @Operation def peek(): String | Null = fact.peek

  object Racing:
    given Sharing.ClaimKind[Racing] = Sharing.ClaimKind(What("racing", null), lockWhenSerialised = true)
    val factHandle: Published.Handle[Racing, "fact"] = Published.handle
    given Published.Handle[Racing, "fact"] = factHandle
