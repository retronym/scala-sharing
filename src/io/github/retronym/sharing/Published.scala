package io.github.retronym.sharing

import java.lang.invoke.{MethodHandles, VarHandle}
import scala.quoted.*

/** A field that publishes others: a writer stores them, then this field with release semantics; a reader loads this
 *  field with acquire semantics, then them. The release store pairs with an acquire load that reads its value, so what
 *  was written before the store is visible after the load: message passing (the MP litmus test) through one field,
 *  without a lock, as Java's `VarHandle.setRelease` and `getAcquire` give, with the field named at compile time.
 *
 *  These are release/acquire in the C++11 sense, which the `VarHandle` access modes adopt. JLS §17 gives
 *  happens-before only to volatiles, monitors and thread start and join; Doug Lea's "Using JDK 9 Memory Order Modes"
 *  describes these modes.
 *
 *  Opaque, so that a plain read or write of the field does not type-check: the only ways in are `acquire`, `release`
 *  and, where the protocol allows a plain access, `plain` and `setPlain`, named so that they can be found and counted.
 *
 *  {{{
 *  class Entry:
 *    private[mypkg] var version: Published[Int] = Published(0)
 *    private var text: String = ""                      // published by `version`
 *    def read(expected: Int): String | Null = if version.acquire == expected then text else null
 *    def write(v: Int, t: String): Unit = { text = t; version.release(v) }
 *
 *  object Entry:
 *    val versionHandle: Published.Handle[Entry, "version"] = Published.handle
 *    given Published.Handle[Entry, "version"] = versionHandle
 *  }}}
 *
 *  The handle is a `val` and the given a reference to it: an alias given computed in place is a lazy val, whose check
 *  every access would pay. The operations are macros: they take the field's owner and name from the call
 *  (`this.version`), and the field's `VarHandle` from the given `Handle[Owner, "name"]` declared in the owner's
 *  companion, so two fields of the same type have their own handles, chosen at compile time. A value class is stored as
 *  its underlying value, the field's erased type. The field must not be `private`: the compiler drops a private field
 *  that no code reads or writes but through the handle, so `handle` rejects one; `private[pkg]` is kept. Supported: `Int`, `Long`, `Boolean`, references, and value classes
 *  over them.
 *
 *  To have the compiler check the fields it publishes too, make them [[Payload]]s.
 *
 *  @tparam T the type of the field's value
 */
opaque type Published[T] = T

object Published extends PublishedOps:

  /** The field's initial value, stored before its owner is shared.
   */
  inline def apply[T](t: T): Published[T] = t

  /** The `VarHandle` of field `F` of class `O`, declared once, as a given in `O`'s companion.
   *
   *  Opaque over the handle itself, not a class holding it: C2 folds a handle in an object's field, as one in a
   *  `static final` field, and inlines `getAcquire` to the `Unsafe` intrinsic, but not one a field further away (measured:
   *  a class around the handle left the call uninlined, 8 times slower).
   *
   *  @tparam O the class that declares the field
   *  @tparam F the field's name, as a literal type
   */
  // a `VarHandle` everywhere (the bound), distinguished by owner and field only for the givens
  opaque type Handle[O, F <: String] <: VarHandle = VarHandle
  object Handle:
    inline def apply[O, F <: String](vh: VarHandle): Handle[O, F] = vh

  /** The handle of field `F` of class `O`, looked up when the companion is initialised. A macro, so that a field that is
   *  not there, or not a `Published`, is a compile error.
   */
  inline def handle[O, F <: String]: Handle[O, F] = ${ PublishedMacros.handleImpl[O, F] }

  /** The value of `S`'s publisher `F` (`S` the owner's singleton type), loaded with acquire semantics: the evidence that
   *  a [[Payload]] of `F` is read after it (`version.acquired`, then `text.after(snap)`).
   *
   *  It need not be used at once: a read after an acquire in program order still sees what the acquire made visible, so
   *  evidence that outlives its block is still sound.
   */
  opaque type Acquired[S, F <: String, T] = T

  /** Evidence that a store runs inside a `publishing` block, before the release that ends it: what `:=` on a [[Payload]]
   *  needs. A block's evidence may only be passed to `:=` in it: the macro rejects any other use.
   */
  opaque type Publishing = AnyRef

/** A field published by its owner's [[Published]] field `F`: stored only in a `publishing` block of `F` (before its
 *  release), read only after an `acquired` of `F` on the same object, or with `plain` where an unordered read is the
 *  protocol (a hint checked again after).
 *
 *  {{{
 *  class Entry:
 *    private[mypkg] var version: Published[Int] = Published(0)
 *    private var text: Payload[Entry, "version", String] = Payload("")
 *
 *    def read(expected: Int): String | Null =
 *      val snap = version.acquired
 *      if snap.value == expected then text.after(snap) else null
 *
 *    def write(v: Int, t: String): Unit =
 *      version.publishing(v):
 *        text := t
 *  }}}
 *
 *  Each misuse is a compile error: a plain read or store of `text`, a store outside the block, a store in a block of
 *  another object's publisher, a read after another object's acquire, and the block's evidence kept past it.
 *
 *  @tparam O the class that declares the field
 *  @tparam F the name of the publisher, as a literal type
 *  @tparam T the type of the field's value
 */
opaque type Payload[O, F <: String, T] = T

object Payload extends PayloadOps:
  /** The field's initial value, stored before its owner is shared. */
  inline def apply[O, F <: String, T](t: T): Payload[O, F, T] = t

/** The operations on a [[Payload]] field, outside the opaque scope as [[PublishedOps]] are.
 */
trait PayloadOps:
  import Published.{Acquired, Publishing}

  extension [O, F <: String, T](inline field: Payload[O, F, T])

    /** The field, read after `snap`, the acquire of its publisher on the same object. A macro, which checks that `snap`
     *  is of the field's owner.
     */
    inline def after[S, V](snap: Acquired[S, F, V]): T = ${ PublishedMacros.afterImpl[S, F, T]('field, 'snap) }

    /** A plain read, with no order: only where the protocol allows it (a hint checked again after). `plain`, as on
     *  [[Published]], after the `VarHandle` access mode.
     */
    inline def plain: T = ${ PublishedMacros.payloadPlainImpl[T]('field) }

    /** Stores `v` in the field, inside a `publishing` block of its publisher, before the release that ends it.
     */
    inline def :=(inline v: T)(using Publishing): Unit = ${ PublishedMacros.payloadStoreImpl[O, F, T]('field, 'v) }

/** The operations on a [[Published]] field, inherited by its companion, so found with no import.
 *
 *  Defined outside the opaque types' scope: an inline method inside it leaves the inliner's proxy for the scope, a dead
 *  load of `Published`'s module, at every call site. The macros see through `Published[T]` and `Handle` where they need
 *  to (each erases to what it is over).
 */
trait PublishedOps:

  extension [T](inline field: Published[T])

    /** The field, loaded with acquire semantics: what was stored before the `release` that stored this value is
     *  seen after it. */
    inline def acquire: T = ${ PublishedMacros.acquireImpl[T]('field) }

    /** Stores `v` in the field with release semantics: what was stored before is seen by a reader that `acquire`s
     *  it. */
    inline def release(inline v: T): Unit = ${ PublishedMacros.releaseImpl[T]('field, 'v) }

    /** A plain read: only where the protocol allows it (a hint checked again after, or one thread). */
    inline def plain: T = ${ PublishedMacros.plainImpl[T]('field) }

    /** A plain store: only where the protocol allows it (a store no other thread acts on yet, or a reset). */
    inline def setPlain(inline v: T): Unit = ${ PublishedMacros.setPlainImpl[T]('field, 'v) }

    /** The field, loaded with acquire semantics, as the evidence that the payloads it publishes are read after:
     *  an `Acquired[owner.type, "field", T]`, whose `value` is the field's value. */
    transparent inline def acquired: Any = ${ PublishedMacros.acquiredImpl[T]('field) }

    /** Runs `body`, which stores this field's payloads (`p := v`), then stores `v` in this field with release
     *  semantics: the stores are ordered before the release by construction. */
    inline def publishing(inline v: T)(inline body: Published.Publishing ?=> Unit): Unit =
      ${ PublishedMacros.publishingImpl[T]('field, 'v, 'body) }

  extension [S, F <: String, T](snap: Published.Acquired[S, F, T])
    /** The publisher's value, as acquired. */
    inline def value: T = ${ PublishedMacros.acquiredValueImpl[S, F, T]('snap) }

  // The accesses the macros expand to, by the field's erased type. Each is typed here, with an `Object` receiver,
  // so that the signature-polymorphic `VarHandle` call has a fixed descriptor (`(Ljava/lang/Object;)I`), as a
  // Java static accessor's would; inlined at the site, with inline parameters, which leave no local for the handle.
  inline def rawGetAcquireInt(inline vh: VarHandle, inline o: Object): Int = vh.getAcquire(o).asInstanceOf[Int]
  inline def rawSetReleaseInt(inline vh: VarHandle, inline o: Object, inline v: Int): Unit = vh.setRelease(o, v)
  inline def rawGetAcquireLong(inline vh: VarHandle, inline o: Object): Long = vh.getAcquire(o).asInstanceOf[Long]
  inline def rawSetReleaseLong(inline vh: VarHandle, inline o: Object, inline v: Long): Unit = vh.setRelease(o, v)
  inline def rawGetAcquireBoolean(inline vh: VarHandle, inline o: Object): Boolean = vh.getAcquire(o).asInstanceOf[Boolean]
  inline def rawSetReleaseBoolean(inline vh: VarHandle, inline o: Object, inline v: Boolean): Unit = vh.setRelease(o, v)
  inline def rawGetAcquireRef(inline vh: VarHandle, inline o: Object): Object = vh.getAcquire(o).asInstanceOf[Object]
  inline def rawSetReleaseRef(inline vh: VarHandle, inline o: Object, inline v: Object): Unit = vh.setRelease(o, v)

private[sharing] object PublishedMacros:
  import Published.Handle

  /** A `Published` field reference: the tree, its owner (the handle's receiver) and the given handle. */
  private[sharing] class FieldRef[T](field: Expr[Any])(using val q: Quotes, t: Type[T]):
    import q.reflect.*

    // the field as written: the inliner wraps it in a binding-free `Inlined`, and, the extension being in the opaque
    // type's scope, in the cast of its opaque proxy (`c.flag.$asInstanceOf$[c.flag & $proxy1.Published[Int]]`)
    private def underlying(t: Term): Term = t match
      case Inlined(_, Nil, e) => underlying(e)
      case TypeApply(Select(e, "$asInstanceOf$"), _) => underlying(e)
      case Typed(e, _) => underlying(e)
      case _ => t

    val ref: Term = underlying(field.asTerm)
    val sym = ref.symbol
    val isFieldRef = ref match
      case Ident(_) | Select(_, _) => true
      case _ => false
    if !isFieldRef || !sym.isValDef || !sym.flags.is(Flags.Mutable) || !sym.owner.isClassDef
    then report.errorAndAbort(s"a `Published` operation needs a `var` field of a class, not ${ref.show}", field)

    val owner: Term = ref match
      case Select(qual, _) => qual
      case _ => This(sym.owner)

    lazy val handle: Term =
      // by its symbol: in this file `TypeRepr.of[Handle[...]]` would see through the opaque type to `VarHandle`
      // and as `Published.Handle`, a member of the module's term: `this.Handle` would see through it too
      val module = Symbol.requiredModule("io.github.retronym.sharing.Published")
      val tycon = module.termRef.select(module.moduleClass.typeMember("Handle"))
      val tpe = tycon.appliedTo(List(sym.owner.typeRef, ConstantType(StringConstant(sym.name))))
      Implicits.search(tpe) match
        case s: ImplicitSearchSuccess => s.tree
        case _ => report.errorAndAbort(
          s"no `${tpe.show}`: declare `given ${tpe.show} = Published.handle` in ${sym.owner.name}'s companion", field)

    // `T`, or for a value class the type of its one field, which is what the field holds once erased
    val valueClass: Option[Symbol] =
      val cls = TypeRepr.of[T].typeSymbol
      if cls.isClassDef && TypeRepr.of[T] <:< TypeRepr.of[AnyVal] && !defn.ScalaPrimitiveValueClasses.contains(cls)
      then Some(cls) else None
    val erased: TypeRepr = valueClass match
      case Some(cls) => TypeRepr.of[T].memberType(cls.fieldMembers.head)
      case None => TypeRepr.of[T]

    def unwrap(v: Term): Term = valueClass match
      case Some(cls) => Select(v, cls.fieldMembers.head)
      case None => v
    def wrap(u: Term): Term = valueClass match
      case Some(cls) => Apply(Select(New(Inferred(TypeRepr.of[T])), cls.primaryConstructor), List(u))
      case None => u

    def kind: String =
      if erased =:= TypeRepr.of[Int] then "Int"
      else if erased =:= TypeRepr.of[Long] then "Long"
      else if erased =:= TypeRepr.of[Boolean] then "Boolean"
      else if erased <:< TypeRepr.of[AnyRef] then "Ref"
      else report.errorAndAbort(s"a `Published` field of ${erased.show} is not supported (Int, Long, Boolean, a reference)", field)

    // `Published.raw<Name>`: inherited from `PublishedOps`
    def internalOp(name: String): Term =
      Select.unique(Ref(Symbol.requiredModule("io.github.retronym.sharing.Published")), "raw" + name.capitalize)
    def vh: Term = handle

  def acquireImpl[T: Type](field: Expr[Published[T]])(using Quotes): Expr[T] =
    val f = FieldRef[T](field)
    import f.q.reflect.*
    val loaded = Apply(f.internalOp(s"getAcquire${f.kind}"), List(f.vh, f.owner))
    val typed = if f.kind == "Ref" then TypeApply(Select.unique(loaded, "asInstanceOf"), List(Inferred(f.erased))) else loaded
    f.wrap(typed).asExprOf[T]

  def releaseImpl[T: Type](field: Expr[Published[T]], v: Expr[T])(using Quotes): Expr[Unit] =
    val f = FieldRef[T](field)
    import f.q.reflect.*
    Apply(f.internalOp(s"setRelease${f.kind}"), List(f.vh, f.owner, f.unwrap(v.asTerm))).asExprOf[Unit]

  // `Published[T]` erases as `T` does: a cast between them, which erasure drops, crosses the opaque type at the
  // site without the inliner's proxy for its scope
  def plainImpl[T: Type](field: Expr[Published[T]])(using Quotes): Expr[T] =
    val f = FieldRef[T](field)
    import f.q.reflect.*
    Select.unique(f.ref, "asInstanceOf").appliedToType(TypeRepr.of[T]).asExprOf[T]

  def setPlainImpl[T: Type](field: Expr[Published[T]], v: Expr[T])(using Quotes): Expr[Unit] =
    val f = FieldRef[T](field)
    import f.q.reflect.*
    Assign(f.ref, Select.unique(v.asTerm, "asInstanceOf").appliedToType(TypeRepr.of[Published[T]])).asExprOf[Unit]

  def handleImpl[O: Type, F <: String: Type](using Quotes): Expr[Handle[O, F]] =
    import quotes.reflect.*
    val cls = TypeRepr.of[O].typeSymbol
    val name = TypeRepr.of[F] match
      case ConstantType(StringConstant(n)) => n
      case t => report.errorAndAbort(s"a field name as a literal type, not ${t.show}")
    val fld = cls.fieldMember(name)
    if !fld.exists then report.errorAndAbort(s"${cls.name} has no field `$name`")
    // the compiler drops a private field that no code reads or writes but through the handle
    if fld.flags.is(Flags.Private) then
      report.errorAndAbort(s"${cls.name}.$name is private, and a private field reached only through its handle is dropped: make it private[${cls.owner.name}]")
    val tpe = TypeRepr.of[O].memberType(fld)
    def ours(p: TypeRepr) = p.typeSymbol.owner.fullName.startsWith("io.github.retronym.sharing")
    val content = tpe match
      case AppliedType(p, List(t)) if p.typeSymbol.name == "Published" && ours(p) => t
      case AppliedType(p, List(_, t)) if p.typeSymbol.name == "Once" && ours(p) => t
      case _ => report.errorAndAbort(s"${cls.name}.$name is a ${tpe.show}, not a `Published` or a `Once`")
    // the erased class of the field: a value class's one field, else the content's own class
    val contentCls = content.typeSymbol
    val erasedCls =
      if content <:< TypeRepr.of[AnyVal] && !defn.ScalaPrimitiveValueClasses.contains(contentCls) then
        content.memberType(contentCls.fieldMembers.head).typeSymbol
      else contentCls
    val erasedClassOf =
      if erasedCls == defn.IntClass then '{ classOf[Int] }
      else if erasedCls == defn.LongClass then '{ classOf[Long] }
      else if erasedCls == defn.BooleanClass then '{ classOf[Boolean] }
      else Literal(ClassOfConstant(content)).asExprOf[Class[?]]
    val ownerClass = Literal(ClassOfConstant(TypeRepr.of[O])).asExprOf[Class[O]]
    '{ Handle[O, F](MethodHandles.privateLookupIn($ownerClass, MethodHandles.lookup())
          .findVarHandle($ownerClass, ${Expr(name)}, $erasedClassOf)) }

  // ---- payloads: `acquired`, `after`, `publishing`

  // an opaque type of this package by its symbol, as a member of the term that owns it: from inside its scope
  // `TypeRepr.of` would see through it
  private[sharing] def opaqueTycon(using q: Quotes)(module: String, name: String): q.reflect.TypeRepr =
    import q.reflect.*
    val m = Symbol.requiredModule(module)
    m.termRef.select(m.moduleClass.typeMember(name))

  private def payloadTycon(using q: Quotes): q.reflect.TypeRepr =
    import q.reflect.*
    // `Payload` is a top-level opaque type: a member of the file's package object
    val pkgObj = Symbol.requiredModule("io.github.retronym.sharing.Published$package")
    pkgObj.termRef.select(pkgObj.moduleClass.typeMember("Payload"))

  def acquiredImpl[T: Type](field: Expr[Published[T]])(using Quotes): Expr[Any] =
    val f = FieldRef[T](field)
    import f.q.reflect.*
    val loaded = acquireImpl[T](field).asTerm
    val acq = opaqueTycon(using f.q)("io.github.retronym.sharing.Published", "Acquired")
      .appliedTo(List(f.owner.tpe, ConstantType(StringConstant(f.sym.name)), TypeRepr.of[T]))
    Select.unique(loaded, "asInstanceOf").appliedToType(acq).asExpr

  def acquiredValueImpl[S: Type, F <: String: Type, T: Type](snap: Expr[Published.Acquired[S, F, T]])(using Quotes): Expr[T] =
    import quotes.reflect.*
    Select.unique(snap.asTerm, "asInstanceOf").appliedToType(TypeRepr.of[T]).asExprOf[T]

  def afterImpl[S: Type, F <: String: Type, T: Type](field: Expr[Any], snap: Expr[Any])(
      using Quotes): Expr[T] =
    val f = FieldRef[T](field)
    import f.q.reflect.*
    if !(TypeRepr.of[S] =:= f.owner.tpe) then
      report.errorAndAbort(
        s"${f.ref.show} is read after the acquire of another object's publisher (${TypeRepr.of[S].show}, not ${f.owner.tpe.show})", snap)
    Select.unique(f.ref, "asInstanceOf").appliedToType(TypeRepr.of[T]).asExprOf[T]

  def payloadPlainImpl[T: Type](field: Expr[Any])(using Quotes): Expr[T] =
    val f = FieldRef[T](field)
    import f.q.reflect.*
    Select.unique(f.ref, "asInstanceOf").appliedToType(TypeRepr.of[T]).asExprOf[T]

  def payloadStoreImpl[O: Type, F <: String: Type, T: Type](field: Expr[Payload[O, F, T]], v: Expr[T])(using Quotes): Expr[Unit] =
    val f = FieldRef[T](field)
    import f.q.reflect.*
    val tpe = payloadTycon(using f.q).appliedTo(List(TypeRepr.of[O], TypeRepr.of[F], TypeRepr.of[T]))
    Assign(f.ref, Select.unique(v.asTerm, "asInstanceOf").appliedToType(tpe)).asExprOf[Unit]

  def publishingImpl[T: Type](field: Expr[Published[T]], v: Expr[T], body: Expr[Published.Publishing ?=> Unit])(
      using Quotes): Expr[Unit] =
    val f = FieldRef[T](field)
    import f.q.reflect.*
    val publisher = f.sym.name
    // the block's evidence parameter, and the block itself
    def closure(t: Term): Option[(Symbol, Term)] = t match
      case Inlined(_, Nil, e) => closure(e)
      case Block(List(dd: DefDef), _: Closure) => dd.rhs.map(rhs => (dd.termParamss.head.params.head.symbol, rhs))
      case _ => None
    closure(body.asTerm) match
      case None => report.errorAndAbort("`publishing` takes a block literal, so that its stores can be checked", body)
      case Some((evidence, rhs)) =>
        new TreeTraverser:
          override def traverseTree(tree: Tree)(owner: Symbol): Unit = tree match
            // a use of the block's evidence that its stores did not consume: it would let a store outlive the block
            case id: Ident if id.symbol == evidence =>
              report.error("the evidence of a `publishing` block may only be passed to `:=` in it", id.pos)
            // a store of a payload: of this publisher, on this object
            case Assign(lhs, _) if lhs.tpe.widen.typeSymbol.name == "Payload" =>
              val (pubOwner, pubName) = lhs.tpe.widen match
                case AppliedType(_, List(_, ConstantType(StringConstant(n)), _)) =>
                  (lhs match { case Select(q, _) => q.tpe; case _ => This(lhs.symbol.owner).tpe }, n)
                case _ => (TypeRepr.of[Nothing], "")
              if pubName != publisher || !(pubOwner =:= f.owner.tpe) then
                report.error(s"${lhs.show} is not published by ${f.ref.show}, so it is not stored in its `publishing` block", lhs.pos)
              super.traverseTree(tree)(owner)
            case _ => super.traverseTree(tree)(owner)
        .traverseTree(rhs)(Symbol.spliceOwner)
    val run = Expr.betaReduce('{ $body(using null.asInstanceOf[Published.Publishing]) })
    '{ $run; ${ releaseImpl[T](field, v) } }
