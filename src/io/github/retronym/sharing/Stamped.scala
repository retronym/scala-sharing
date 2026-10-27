package io.github.retronym.sharing

import scala.quoted.*

/** A value with the stamp it was computed at, as one immutable object, held in one `var` of the cache's owner.
 *
 *  Bloch's racy single-check idiom (Effective Java, Item 83) over an immutable holder (JCIP §3.4.2): a reader that
 *  misses computes and stores, and two may both compute; the holder's final fields (JLS §17.5) make that safe without
 *  a volatile.
 *
 *  A stamp says what the value was computed against: a generation of the inputs, a run, a version. The value is valid
 *  while its stamp is the current one.
 *
 *  Both fields are final, so a reader that loads the reference sees the pair whole, however it was published: no
 *  fences, no test of a [[Mode]], and the same code with one thread or several. A writer replaces the stamped value. A
 *  reader can never see a current stamp with an older value, nor a stamp and a value from two writers. The price is an
 *  allocation per miss and a dependent load per hit (the owner's field, then the stamped value's).
 *
 *  {{{
 *  sealed trait PerGeneration // the kind of stamp: a phantom type
 *
 *  class Library(using Stamp[PerGeneration]):
 *    private var exports: Stamped[PerGeneration, List[String]] = Stamped.Empty
 *    def exportedNames: List[String] =
 *      exports.cached:
 *        computeExports()
 *  }}}
 *
 *  A stamp is an `Int`, never `0` for a current one: [[Stamped.Empty]], what a field starts with, has stamp `0`. `K` is
 *  the stamp's kind, a phantom type: the operations take the current stamp of that kind as a given [[Stamp]]`[K]`,
 *  which the owner supplies (typically an `inline given` that reads a counter). A call site therefore names no stamp,
 *  and a field is never read with one kind of stamp and written with another.
 *
 *  @tparam K the kind of the stamp (a phantom type)
 *  @tparam T the type of the value
 *  @param stamp the stamp the value was computed at
 *  @param value the value
 */
final class Stamped[+K, +T](val stamp: Int, val value: T):
  override def toString: String = s"Stamped($stamp, $value)"

/** The current stamp of kind `K`: the given that the operations of [[Stamped]] take. A value class, so an inline given
 *  of it is the `Int` it reads (as [[Mode]] is its `Boolean`).
 *
 *  {{{
 *  inline given (using g: Generation): Stamp[PerGeneration] = Stamp(g.current)
 *  }}}
 *
 *  @tparam K the kind of the stamp (a phantom type)
 *  @param value the current stamp, never `0`
 */
final class Stamp[K](val value: Int) extends AnyVal

object Stamped:

  /** The stamped value of a cache not yet computed: stamp `0`, which is never current. */
  // a cast of the stamped value, not of `null` to `Nothing`, which throws
  val Empty: Stamped[Nothing, Nothing] = Stamped[Nothing, Null](0, null).asInstanceOf[Stamped[Nothing, Nothing]]

  extension [K, T](inline field: Stamped[K, T])

    /** The value of the cache in `field`, a `var` of its owner, valid while its stamp is the current `Stamp[K]`. On a miss,
     *  the value `compute`d is stored in `field` with that stamp and returned.
     *
     *  {{{
     *  private var exports: Stamped[PerGeneration, List[String]] = Stamped.Empty
     *  def exportedNames(using Stamp[PerGeneration]): List[String] =
     *    exports.cached:
     *      computeExports()
     *  }}}
     *
     *  Expands to
     *
     *  {{{
     *  val n = summon[Stamp[PerGeneration]].value
     *  val c = exports
     *  if c.stamp == n then c.value
     *  else
     *    val v = computeExports()
     *    exports = new Stamped(n, v)
     *    v
     *  }}}
     *
     *  A macro, so that it can write the field it was called on; anything but a `var` is an error.
     *
     *  @param compute computes the value on a miss
     */
    inline def cached(using inline now: Stamp[K])(inline compute: T): T =
      val n = now.value // read here, not quoted in the splice, which left the given's package object loaded at the site
      cachedAt(field, n, (s, _) => s == n, _ => true, compute)

    /** Like [[cached]], but the value computed is stored only if `keep` holds of it, for example when it is not
     *  provisional. Otherwise the old stamped value stays: it is still a correct one, for its own stamp.
     *
     *  {{{
     *  // computed from inputs that may still change, so not kept
     *  exports.cachedIf(_ => !hasPendingInputs):
     *    computeExports()
     *  }}}
     *
     *  Expands as `cached` does, with the store under `if keep(v)`. The value is returned whether or not it is kept.
     *
     *  @param keep whether to store the value just computed
     */
    inline def cachedIf(inline keep: T => Boolean)(using inline now: Stamp[K])(inline compute: T): T =
      val n = now.value // as in `cached`
      cachedAt(field, n, (s, _) => s == n, keep, compute)

    /** Like [[cached]], but a hit is whatever `isCurrent(stamp, value)` says: for a validity wider than one stamp, or for a
     *  value that an invalidation may clear (to [[Stamped.Empty]], whose value is `null`).
     *
     *  {{{
     *  // valid while the inputs are unchanged since the stamp it was computed at
     *  index.cachedWhen((stamp, idx) => idx != null && !inputsChangedSince(stamp)):
     *    buildIndex()
     *  }}}
     *
     *  Expands as `cached` does, with `isCurrent(c.stamp, c.value)` in place of `c.stamp == n`.
     *
     *  @param isCurrent whether the stored stamp and value are still good; a miss stores the current stamp
     */
    inline def cachedWhen(inline isCurrent: (Int, T) => Boolean)(using inline now: Stamp[K])(inline compute: T): T =
      val n = now.value // as in `cached`
      cachedAt(field, n, isCurrent, _ => true, compute)

    /** The value of the cache in `field` if its stamp is the current one, `orElse` otherwise: a read that neither computes
     *  nor stores.
     *
     *  @param orElse the result on a miss; not stored
     */
    inline def peek(using inline now: Stamp[K])(inline orElse: T): T =
      val n = now.value
      val c = field
      if c.stamp == n then c.value else orElse

  // the macro, under `cached`, `cachedIf` and `cachedWhen`: `n` is the current stamp, a local of theirs, not bound again
  private inline def cachedAt[K, T](inline field: Stamped[K, T], n: Int, inline isCurrent: (Int, T) => Boolean,
      inline keep: T => Boolean, inline compute: T): T =
    ${ cachedImpl('field, 'n, 'isCurrent, 'keep, 'compute) }

  private def cachedImpl[K: Type, T: Type](field: Expr[Stamped[K, T]], n: Expr[Int], isCurrent: Expr[(Int, T) => Boolean],
      keep: Expr[T => Boolean], compute: Expr[T])(
      using Quotes): Expr[T] =
    import quotes.reflect.*
    def underlying(t: Term): Term = t match
      case Inlined(_, Nil, e) => underlying(e)
      case _ => t
    val ref = underlying(field.asTerm)
    ref match
      case Ident(_) | Select(_, _) if ref.symbol.flags.is(Flags.Mutable) && !ref.symbol.flags.is(Flags.Method) =>
      case _ => report.errorAndAbort(s"a cache in a `Stamped` must be a `var`, not ${ref.show}", field)
    def store(n: Expr[Int], v: Expr[T]): Expr[Unit] = Assign(ref, '{ new Stamped[K, T]($n, $v) }.asTerm).asExprOf[Unit]
    '{
      val c = $field
      if ${ Expr.betaReduce('{ $isCurrent(c.stamp, c.value) }) } then c.value
      else
        val v = $compute
        if ${ Expr.betaReduce('{ $keep(v) }) } then ${ store(n, 'v) }
        v
    }
