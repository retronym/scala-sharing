package io.github.retronym.sharing

import java.util.concurrent.ConcurrentHashMap

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.junit.Test

import SharingLincheckTest.*
import ToyCompilerLincheckTest.{BatchToy, Toy}

/** `SharingLincheckTest`'s sections and `ToyCompilerLincheckTest`'s toy on the registry for parallel threads,
 *  `PerKeyClaims` (one claimant per thread, each able to back off), and one section of its own: a wait-for cycle,
 *  which only this registry checks for.
 *
 *  Stress only: a wait for a claim is timed, which model checking does not explore. Per section, 10 scenarios of 3
 *  threads of 3 calls, each run 1000 times; the cycle 5 scenarios of 50 runs, since a claimant finds a cycle only at
 *  its next look, a wait slice (5 ms) after the wait began.
 */
class SharingOnPerKeyClaimsLincheckTest:
  import SharingOnPerKeyClaimsLincheckTest.*

  @Test def claim(): Unit = stress(classOf[ClaimSpec], classOf[RootsClaim])
  @Test def once(): Unit = stress(classOf[OnceSpec], classOf[RootsOnce])
  @Test def blackHole(): Unit = stress(classOf[BlackHoleSpec], classOf[RootsBlackHole])
  @Test def claimBacksOffAWaitForCycle(): Unit = stress(classOf[CycleSpec], classOf[RootsCycle], scenarios = 5, runs = 50)
  @Test def toyCompiler(): Unit = stress(classOf[BatchToy], classOf[RootsToy])

object SharingOnPerKeyClaimsLincheckTest:

  given Mode = Mode.Concurrent

  /** Per-key claims whose claimants are one per thread. */
  def roots(): ClaimRegistry =
    val claimants = ConcurrentHashMap[Thread, Claimant]()
    PerKeyClaims(() => claimants.computeIfAbsent(Thread.currentThread, _ => Claimant()).nn)

  class RootsClaim extends Claim(using roots())
  class RootsOnce extends BlackHoleOnce(using roots())
  class RootsBlackHole extends BlackHole(using roots())
  class RootsToy extends Toy(using Mode.Concurrent, roots())

  /** Two claimants that claim two keys in opposite orders can close a wait-for cycle. One of them backs off and its
   *  call restarts, rather than both waiting for ever, a hang Lincheck reports. A call restarts until it does not back
   *  off, so what it promises is what a claim that never backs off does: one more on the counter.
   */
  class CycleSpec extends CounterSpec:
    def ab(): Int = next()
    def ba(): Int = next()

  class RootsCycle:
    private given ClaimRegistry = roots()
    private val a, b = Key()
    private val counter = Counter()

    @Operation def ab(): Int = restarting(Sharing.claim(a)(Sharing.claim(b)(counter.count())))
    @Operation def ba(): Int = restarting(Sharing.claim(b)(Sharing.claim(a)(counter.count())))
