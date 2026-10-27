package io.github.retronym.sharing

import scala.quoted.*

/** A value with the stamp it was computed at, as one immutable object, held in one `var` of the cache's owner
 *  (docs/_docs/internals/interactive-multi-reader.md §4.4.1).
 *
 *  Both fields are final, so a reader that loads the reference sees the pair whole, however it was published: no
 *  fences, no test of a `Mode`, and the code is the same with one thread or several. A writer replaces the stamped
 *  value; a reader can never see a current stamp with an older value, nor a stamp and value from two writers. The
 *  price is an allocation per miss and a dependent load per hit (the owner's field, then the stamped value's).
 *
 *  A stamp is an `Int`, never `0` for a current one: `Stamped.Empty`, what a field starts with, has stamp `0`. `K` is
 *  the stamp's kind, a phantom (in dotc, `PerRun` or `PerPeriod`): the operations take the current stamp of that kind
 *  as a given `Stamp[K]`, which the owner supplies (in dotc from the context, `dotc.runStamp`, `dotc.periodStamp`), so
 *  a call site names no stamp and a field is never read with one kind and written with another.
 */
final class Stamped[+K, +T](val stamp: Int, val value: T):
  override def toString: String = s"Stamped($stamp, $value)"

/** The current stamp of kind `K`, the given the operations take: a value class, so an inline given of it is the `Int`
 *  it reads (as `Mode` is the `Boolean`). */
final class Stamp[K](val value: Int) extends AnyVal

object Stamped:

  /** The stamped value of a cache not yet computed: stamp `0`, which is never current. */
  // a cast of the stamped value, not of `null` to `Nothing`, which throws
  val Empty: Stamped[Nothing, Nothing] = Stamped[Nothing, Null](0, null).asInstanceOf[Stamped[Nothing, Nothing]]

  extension [K, T](inline field: Stamped[K, T])

    /** The value of the cache in `field`, a `var` of its owner, valid while its stamp is the current `Stamp[K]`; on a
     *  miss the value `compute`d, stored in `field` with that stamp, and returned.
     *  {{{
     *  private var myBaseClasses: Stamped[PerPeriod, List[ClassSymbol]] = Stamped.Empty
     *  override final def baseClasses(using Context): List[ClassSymbol] =
     *    myBaseClasses.cached:
     *      ... // the base classes, computed
     *  }}}
     *  Expands to (with dotc's `periodStamp` given)
     *  {{{
     *  val n = ctx.period.rawCode
     *  val c = myBaseClasses
     *  if c.stamp == n then c.value
     *  else
     *    val v = ... // the base classes, computed
     *    myBaseClasses = new Stamped(n, v)
     *    v
     *  }}}
     *  A macro, so that it can write the field it was called on; anything but a `var` is an error.
     */
    inline def cached(using inline now: Stamp[K])(inline compute: T): T =
      val n = now.value // read here, not quoted in the splice, which left the given's package object loaded at the site
      cachedAt(field, n, (s, _) => s == n, _ => true, compute)

    /** `cached`, storing the value computed only when `keep` holds of it (it is not provisional, say), and leaving the
     *  old stamped value otherwise: it is still a correct one, for its own stamp.
     *  {{{
     *  // OrType.atoms: a provisional type's atoms may change, so they are not kept
     *  myAtoms.cachedIf(_ => !isProvisional):
     *    computeAtoms()
     *  }}}
     *  Expands as `cached` does, the store under `if keep(v)`.
     */
    inline def cachedIf(inline keep: T => Boolean)(using inline now: Stamp[K])(inline compute: T): T =
      val n = now.value // as in `cached`
      cachedAt(field, n, (s, _) => s == n, keep, compute)

    /** `cached`, with a hit when `isCurrent(stamp, value)`: for a validity wider than one stamp, or a value an
     *  invalidation may clear (to `Stamped.Empty`, whose value is `null`).
     *  {{{
     *  // ClassDenotation.baseTypeCache: valid while the base types are the same as at its period
     *  myBaseTypeCache.cachedWhen((stamp, cache) => cache != null && currentHasSameBaseTypesAs(Period.fromCode(stamp))):
     *    ctx.base.sharing.newEqHashMap()
     *  }}}
     *  Expands as `cached` does, with `isCurrent(c.stamp, c.value)` for `c.stamp == n`.
     */
    inline def cachedWhen(inline isCurrent: (Int, T) => Boolean)(using inline now: Stamp[K])(inline compute: T): T =
      val n = now.value // as in `cached`
      cachedAt(field, n, isCurrent, _ => true, compute)

    /** The value of the cache in `field` if its stamp is the current one, `orElse` otherwise: a read that neither
     *  computes nor stores.
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
