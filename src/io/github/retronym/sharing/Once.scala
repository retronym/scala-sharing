package io.github.retronym.sharing

import scala.quoted.*

/** A field holding a lazy fact, computed once by the one writer of its owner while other threads wait for it: `null`
 *  until the value is stored, then the value.
 *
 *  Opaque, so that a plain read or write of the field does not type-check: the value is stored only by `get`, after
 *  `compute` returns, inside the claim on the owner, with release semantics, and read with acquire semantics. What the
 *  value refers to is seen whole by a reader that finds it, with no further rule on how it is built. The discipline that
 *  [[Sharing.once]] leaves to the owner (a store only under the claim, the field the same on both sides, a value
 *  published whole) is the type's.
 *
 *  {{{
 *  class Symbol(name: String)(using Mode, ClaimRegistry):
 *    private[mypkg] var myInfo: Once[this.type, Info] = Once.empty
 *
 *    def info: Info = myInfo.get(computeInfo())
 *
 *  object Symbol:
 *    given Sharing.ClaimKind[Symbol] = Sharing.ClaimKind(What("computing info", null), lockWhenSerialised = true)
 *    val myInfoHandle: Published.Handle[Symbol, "myInfo"] = Published.handle
 *    given Published.Handle[Symbol, "myInfo"] = myInfoHandle
 *  }}}
 *
 *  The key of the claim is the field's owner, the `K` of its type (`this.type` where it is declared), so that `compute`
 *  is given `Holds[K]`, the evidence for the owner's other writer state (a [[Sharing.BlackHole]], say). The field needs
 *  a `Published.Handle`, declared as for a [[Published]] field, and must not be `private`. A fact keyed by another
 *  object, or that is not one reference, is [[Sharing.once]]'s or [[Sharing.claim]]'s.
 *
 *  In [[Mode.Batch]] the accesses are plain, so a hit is a field read and a null test, as before concurrency; in
 *  [[Mode.Concurrent]] the read is an acquire.
 *
 *  @tparam K the field's owner, as a singleton type
 *  @tparam T the type of the fact
 */
opaque type Once[K, T <: AnyRef] = T | Null

object Once extends OnceOps:
  /** The field's initial value: no fact yet. */
  inline def empty[K, T <: AnyRef]: Once[K, T] = null

/** The operations on a [[Once]] field, inherited by its companion, so found with no import. Outside the opaque type's
 *  scope, as [[PublishedOps]] are.
 */
trait OnceOps:
  import Sharing.{ClaimKind, Holds}

  extension [K <: AnyRef & Singleton, T <: AnyRef](inline field: Once[K, T])

    /** The fact, or, when there is none, the value of `compute`, run as the one writer of the field's owner (see
     *  [[Sharing.claim]]) and stored. The field is read again inside the claim first, since another thread may have
     *  stored the value meanwhile. Another thread waits for an owner's computation at the claim, so `compute` is entered
     *  again only by its owner, re-entering its own computation: the place for a cycle test.
     *
     *  Expands to
     *
     *  {{{
     *  val v = if mode.concurrent then <acquire field> else <plain field>
     *  if v != null then v
     *  else Sharing.claim(owner):
     *    val v1 = <plain field>                      // ordered by the claim
     *    if v1 != null then v1
     *    else { val r = compute; if mode.concurrent then <release field := r> else <field := r>; r }
     *  }}}
     *
     *  @param compute computes the value, as the one writer; the library stores it
     */
    inline def get(inline compute: Holds[K] ?=> T)(using inline kind: ClaimKind[K])(
        using inline mode: Mode, inline registry: ClaimRegistry): T =
      val v = if mode.concurrent then Once.acquireRaw[T](field) else Once.plainRaw[T](field)
      if v != null then v.asInstanceOf[T]
      else Sharing.claim(Once.owner[K](field)):
        val v1 = Once.plainRaw[T](field)
        if v1 != null then v1.asInstanceOf[T]
        else
          val r = compute
          if mode.concurrent then Once.releaseRaw[T](field, r) else Once.setPlainRaw[K, T](field, r)
          r

    /** The fact if it is there, else `null`, without claiming: read with acquire semantics in a concurrent mode, so
     *  what it refers to is seen whole. */
    inline def peek(using inline mode: Mode): T | Null =
      if mode.concurrent then Once.acquireRaw[T](field) else Once.plainRaw[T](field)

    /** Forgets the fact, as the one writer, so that the next `get` computes it again. */
    inline def reset()(using Holds[K])(using inline mode: Mode): Unit =
      if mode.concurrent then Once.releaseRaw[T](field, null) else Once.setPlainRaw[K, T](field, null)

    /** A plain store: only where the protocol allows it (before the owner is shared, or one thread). */
    inline def setPlain(inline v: T | Null): Unit = Once.setPlainRaw[K, T](field, v)

  // The accesses the operations expand to, each a macro over the field reference, as `Published`'s are.
  inline def owner[K](inline field: Any): K = ${ OnceMacros.ownerImpl[K]('field) }
  inline def acquireRaw[T](inline field: Any): T | Null = ${ OnceMacros.acquireImpl[T]('field) }
  inline def plainRaw[T](inline field: Any): T | Null = ${ OnceMacros.plainImpl[T]('field) }
  inline def releaseRaw[T](inline field: Any, inline v: T | Null): Unit = ${ OnceMacros.releaseImpl[T]('field, 'v) }
  inline def setPlainRaw[K, T](inline field: Any, inline v: T | Null): Unit = ${ OnceMacros.setPlainImpl[K, T]('field, 'v) }

private[sharing] object OnceMacros:
  import PublishedMacros.FieldRef

  // `Once` is a top-level opaque type: a member of the file's package object, from which `TypeRepr.of` would see
  // through it
  private def onceTycon(using q: Quotes): q.reflect.TypeRepr =
    import q.reflect.*
    val pkgObj = Symbol.requiredModule("io.github.retronym.sharing.Once$package")
    pkgObj.termRef.select(pkgObj.moduleClass.typeMember("Once"))

  /** The field's owner, which must be the `K` of its type: the claim is taken on it, and `compute` given `Holds[K]`. */
  def ownerImpl[K: Type](field: Expr[Any])(using Quotes): Expr[K] =
    val f = FieldRef[AnyRef](field)
    import f.q.reflect.*
    if !(f.owner.tpe =:= TypeRepr.of[K]) then
      report.errorAndAbort(s"${f.ref.show} is a `Once` keyed by ${TypeRepr.of[K].show}, not by its owner ${f.owner.tpe.show}: declare it `Once[this.type, ...]`", field)
    f.owner.asExprOf[K]

  def acquireImpl[T: Type](field: Expr[Any])(using Quotes): Expr[T | Null] =
    val f = FieldRef[T](field)
    import f.q.reflect.*
    val loaded = Apply(f.internalOp("getAcquireRef"), List(f.vh, f.owner))
    Select.unique(loaded, "asInstanceOf").appliedToType(TypeRepr.of[T | Null]).asExprOf[T | Null]

  def plainImpl[T: Type](field: Expr[Any])(using Quotes): Expr[T | Null] =
    val f = FieldRef[T](field)
    import f.q.reflect.*
    Select.unique(f.ref, "asInstanceOf").appliedToType(TypeRepr.of[T | Null]).asExprOf[T | Null]

  def releaseImpl[T: Type](field: Expr[Any], v: Expr[T | Null])(using Quotes): Expr[Unit] =
    val f = FieldRef[T](field)
    import f.q.reflect.*
    Apply(f.internalOp("setReleaseRef"), List(f.vh, f.owner, v.asTerm)).asExprOf[Unit]

  def setPlainImpl[K: Type, T: Type](field: Expr[Any], v: Expr[T | Null])(using Quotes): Expr[Unit] =
    val f = FieldRef[T](field)
    import f.q.reflect.*
    val tpe = onceTycon(using f.q).appliedTo(List(TypeRepr.of[K], TypeRepr.of[T]))
    Assign(f.ref, Select.unique(v.asTerm, "asInstanceOf").appliedToType(tpe)).asExprOf[Unit]
