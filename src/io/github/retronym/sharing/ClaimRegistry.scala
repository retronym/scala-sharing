package io.github.retronym.sharing

/** Who holds the claims on keys when threads are concurrent: what `Sharing.claim` calls on a concurrent mode, given
 *  beside the `Mode` by the owner of the shared state. dotc's are the completion lock (serialised readers) and its
 *  completion roots (parallel readers). Never reached in `Mode.Batch`.
 */
trait ClaimRegistry:
  /** `op` as the one writer of the lazy facts keyed by `key`: holding the claim on it, or the coarse lock if `kind`
   *  says serialised threads take it, or as is. Re-entry by the holder runs `op` at once. A wait that would close a
   *  wait-for cycle is reported against `cycleSubject` (to a `CycleHandler`). */
  def claim[T](key: AnyRef, kind: Sharing.ClaimKind[Nothing], cycleSubject: AnyRef, op: () => T): T

/** What a claim does when a wait for it would close a wait-for cycle between threads: it throws, so that the thread
 *  unwinds and releases what it holds (dotc backs off and restarts the reader's request). */
trait CycleHandler:
  def waitCycle(subject: AnyRef): Nothing

/** A thread's work unwinding because it was cancelled or backs off from a wait-for cycle (a `CycleHandler` may throw
 *  one): not a failure of what it was computing, which it leaves as it found it, and caught where the work restarts.
 *  dotc's `CompilationUnit.CancelledCompilation` is one.
 */
class Cancelled extends scala.util.control.ControlThrowable

/** What the holder of a claim is doing: a kind and an optional subject, rendered only when a waiter reports it.
 *  `renderSubject` renders a subject that is not a `String` or a `Label`.
 */
class What(val kind: String, val subject: AnyRef | Null):
  final def render: String = subject match
    case null => kind
    case str: String => s"$kind $str"
    case l: Label => s"$kind ${l.text}"
    case other => s"$kind ${renderSubject(other)}"
  protected def renderSubject(subject: AnyRef): String = subject.getClass.getSimpleName
  override def toString: String = render

/** A subject computed when a waiter reports it (e.g. what the holder is doing by then). */
final class Label(f: () => String):
  def text: String = f()
