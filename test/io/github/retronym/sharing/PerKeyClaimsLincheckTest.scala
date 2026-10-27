package io.github.retronym.sharing

import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, CyclicBarrier, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Assert.*
import org.junit.Test

/** `PerKeyClaims`' wait-for check: it must report a cycle only for a real one.
 *
 *  The operations take keys in ways that never deadlock: `nested` holds k1 and takes k2, `single` takes k2 and
 *  releases it, then takes k1 alone. A claimant that is woken from a wait on k2 still has that claim in `waitingOn`
 *  until it runs, and the claimant that released k2 and now asks for k1 (held by the woken one) could read it as a
 *  cycle. The operations return whether `cycle()` was called; sequentially it never is, so any `true` fails the
 *  linearizability check. No claimant can abort, so the cycle path is live.
 *
 *  Stress runs real threads (the waits are timed, which model checking does not explore). The real cycle, in
 *  `realCycleIsRaisedByExactlyOne`, must still be raised, once.
 */
class PerKeyClaimsLincheckTest:
  import PerKeyClaimsLincheckTest.*

  @Test def stress(): Unit =
    LinChecker.check(classOf[Roots], StressOptions().iterations(50).invocationsPerIteration(500).threads(3).actorsPerThread(4))

  @Test def realCycleIsRaisedByExactlyOne(): Unit =
    val claimants = new ConcurrentHashMap[Thread, Claimant]()
    val roots = PerKeyClaims(() => claimants.computeIfAbsent(Thread.currentThread, _ => Steadfast()).nn)
    val k1, k2 = new Object
    val barrier = new CyclicBarrier(2)
    val raised = new AtomicInteger
    val done = new CountDownLatch(2)
    class Cycle extends RuntimeException
    def party(first: Object, second: Object): Thread =
      val t = new Thread((() =>
        try
          roots.acquire(first, what, () => throw new Cycle)
          try
            barrier.await(): Unit
            try
              roots.acquire(second, what, () => throw new Cycle)
              roots.release(second)
            catch case _: Cycle => raised.incrementAndGet()
          finally roots.release(first)
        finally done.countDown()): Runnable)
      t.setDaemon(true)
      t.start()
      t
    party(k1, k2); party(k2, k1)
    assertTrue("both claimants should finish", done.await(30, TimeUnit.SECONDS))
    assertEquals("one claimant of the cycle raises it", 1, raised.get)

object PerKeyClaimsLincheckTest:
  /** A claimant that cannot be a cycle's victim: it waits, or reports the cycle. */
  final class Steadfast extends Claimant:
    override def canAbort: Boolean = false

  val what: What = What("signature", null)

  class Roots:
    private val claimants = new ConcurrentHashMap[Thread, Claimant]()
    private val roots = PerKeyClaims(() => claimants.computeIfAbsent(Thread.currentThread, _ => Steadfast()).nn)
    private val k1, k2 = new Object

    private final class Cycle extends RuntimeException

    // true if `cycle` was called (the flag is the operation's own, claimants run concurrently)
    private def attempt(op: (() => Nothing) => Unit): Boolean =
      var cycled = false
      try op(() => { cycled = true; throw new Cycle }) catch case _: Cycle => ()
      cycled

    @Operation def nested(): Boolean = attempt { cycle =>
      roots.acquire(k1, what, cycle)
      try
        roots.acquire(k2, what, cycle)
        roots.release(k2)
      finally roots.release(k1)
    }

    @Operation def single(): Boolean = attempt { cycle =>
      roots.acquire(k2, what, cycle)
      roots.release(k2)
      roots.acquire(k1, what, cycle)
      roots.release(k1)
    }
