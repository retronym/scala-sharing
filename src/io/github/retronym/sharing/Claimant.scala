package io.github.retronym.sharing

/** A party that holds and waits for claims in `PerKeyClaims`: one per thread, or one per request that may move
 *  between threads. `PerKeyClaims` compares claimants by identity.
 *
 *  What a claimant does when waiting for a claim would close a wait-for cycle (another claimant holds the claim, and
 *  is waiting, directly or through others, for one of this claimant's):
 *
 *  - one that `canBackOff` throws `Cancelled` at once, so its claims unwind and its work restarts;
 *  - otherwise, while some party of the cycle can back off, it waits for that one to;
 *  - otherwise one party of the cycle (the same for every party, by a fixed order of their threads) reports it to the
 *    claim's `cycle` (a `CycleHandler`), once it has seen the same cycle at two looks.
 *
 *  In the compiler a claimant typing a method body backs off (its request restarts), and one inside a signature's
 *  completion does not: a cycle between signatures is a cyclic reference, reported as an error.
 *
 *  @param thread the thread whose identity orders the parties of a cycle
 */
class Claimant(val thread: Thread = Thread.currentThread):
  /** The claim this claimant is waiting for, or null: the edges of the wait-for graph. */
  @volatile var waitingOn: PerKeyClaims.Claim | Null = null

  /** How many times this claimant backed off from a wait-for cycle. */
  @volatile var backedOff: Int = 0

  /** Whether this claimant backs off from a wait-for cycle by throwing `Cancelled`. Read at every look of a wait. */
  def canBackOff: Boolean = true

  /** Called once the claimant has the claim it waited `nanos` for, with what the holder was doing when the wait began
   *  and who it was. For lock-wait statistics; does nothing by default. */
  def recordWait(nanos: Long, holderWhat: String, holderName: String): Unit = ()

/** A `CycleHandler` that backs off: throws `Cancelled`, as a claimant that can back off does. */
object BackOff extends CycleHandler:
  def waitCycle(subject: AnyRef): Nothing = throw Cancelled()
