package io.github.retronym.sharing

import scala.annotation.targetName

/** A value reached only with the evidence of a claim on `K` ([[Sharing.Holds]]): `c()` reads it, `Claimed(v)` makes
 *  the value to store. [[Guarded]] is the same for a monitor.
 *
 *  The value itself, so a field of this type costs what the field did, and `c()` expands to the field read. A field
 *  keeps a mutable collection (a `val`) or a value replaced by the one writer (a `var`):
 *
 *  {{{
 *  // an unpickler's state, under the claim on the file it reads: `Sharing.claimAt(this)(file, kind, this)(...)`
 *  private val symAtAddr: Claimed[this.type, mutable.HashMap[Addr, Symbol]] = Claimed.initial(mutable.HashMap())
 *  private var rootOwner: Claimed[this.type, Symbol] = Claimed.initial(NoSymbol)
 *
 *  def register(addr: Addr, sym: Symbol)(using Sharing.Holds[this.type]): Unit = symAtAddr()(addr) = sym
 *  def enterQuote(owner: Symbol)(using Sharing.Holds[this.type]): Unit = rootOwner = Claimed(owner)
 *  }}}
 *
 *  `symAtAddr()` or `Claimed(owner)` outside the claim is an error: "this code must run as the one writer of
 *  (TreeUnpickler.this : TreeUnpickler)". The evidence is an inline parameter that neither uses, so they cost nothing
 *  at run time, and a `given` that makes it with [[Sharing.unguarded]] is not evaluated.
 *
 *  `K` is the claim's key, usually `this.type`: the owner, claimed by [[Sharing.claim]] on itself or by
 *  [[Sharing.claimAt]] on a key computed from it. `Claimed.initial` makes a value with no evidence, for a field's
 *  initialiser, before its owner is shared; nothing stops it elsewhere, so look for it as for `unguarded`. What `c()`
 *  returns is outside the claim: keep a mutable collection's use inside it.
 *
 *  @tparam K the claim's key, usually the owner's singleton type
 *  @tparam T the type of the value
 */
opaque type Claimed[K, T] = T

object Claimed extends ClaimedOps

/** The operations on a [[Claimed]] value, inherited by its companion, so found with no import. Outside the opaque
 *  type's scope, as [[OnceOps]] are: inside it, the inliner's proxy for the scope leaves a dead load of the package
 *  object at each site. A cast to or from the opaque type erases to nothing.
 */
trait ClaimedOps:
  /** The value of a field in its initialiser, before its owner is shared, with no evidence. */
  inline def initial[K, T](inline t: T): Claimed[K, T] = t.asInstanceOf[Claimed[K, T]]

  /** The value to store in a field, as the one writer: `rootOwner = Claimed(owner)`. */
  inline def apply[K, T](inline t: T)(using inline h: Sharing.Holds[K]): Claimed[K, T] = t.asInstanceOf[Claimed[K, T]]

  extension [K, T](c: Claimed[K, T])
    /** The value, as the one writer. */
    @targetName("read") inline def apply()(using inline h: Sharing.Holds[K]): T = c.asInstanceOf[T]
