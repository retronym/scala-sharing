package io.github.retronym.sharing

/** Whether the state a `Sharing` operation guards is shared between threads, chosen when its owner (dotc's
 *  `ContextBase`) is made and never changed. The operations take it as a given, which the owner supplies from a final
 *  field; `Mode.Batch` is one thread and no locking.
 *
 *  How concurrent threads take a claim, serialised under one coarse lock or in parallel with a claim per key, is the
 *  `ClaimRegistry`'s choice, not the mode's: the operations test only `concurrent`.
 *
 *  A value class over that `Boolean`, so that the test on a hot path is the field's read and an `ifeq`, the code a
 *  `Boolean` field had. An opaque type would add the load of its package object (the inliner's proxy for the opaque
 *  scope), and a test of an `Int` a materialised `Boolean`.
 */
final class Mode(val concurrent: Boolean) extends AnyVal:
  override def toString: String = if concurrent then "Mode.Concurrent" else "Mode.Batch"

object Mode:
  val Batch: Mode = Mode(false)
  val Concurrent: Mode = Mode(true)
