package io.github.retronym.sharing

import scala.annotation.implicitNotFound

/** Operations on state shared between threads (or not, in `Mode.Batch`), with evidence types that make the guarded
 *  state reachable only from inside them. Nothing here depends on the compiler: dotc supplies the `Mode` and
 *  `ClaimRegistry` givens from its `ContextBase` (`dotty.tools.dotc.sharingMode`, `claimRegistry`) and re-exports
 *  these members from `dotty.tools.dotc.core.Sharing`. See docs/_docs/internals/interactive-multi-reader.md §11.
 *
 *  Every operation that takes code to run is an `inline def` whose `Mode.Batch` branch is that code as it was before
 *  concurrency: a call site compiles to a test of the given mode (a final field of its owner) and the original
 *  expression, and allocates nothing. The concurrent branches call the `ClaimRegistry`.
 *
 *  Which operation, by what the state is:
 *
 *  - a lazy fact, computed once by one writer while other threads wait for it: `once` (a value read lock free once
 *    it is there), `claim` (anything else), with a `ClaimKind` given beside the key's class and, for re-entry, a
 *    `BlackHole`. The writer's state is reached through methods that take the claim's evidence, `Holds[key.type]`.
 *  - a short critical section over a collection or a few fields: a `Guard` whose fields are `Guarded`, or
 *    `serialised` and `locking` on another object's monitor. The guarded state is reached with `Monitor[lock.type]`.
 *  - a value paired with the stamp it was computed at: `Stamped`.
 */
object Sharing:

  /** Evidence that the code it is given to is the one writer of the lazy facts keyed by `key: K`: it runs in
   *  `claim(key)`. A capability in the manner of Clang's `REQUIRES`: state that only the writer may touch is
   *  reachable only through a method that takes one, so a touch outside the claim does not compile. Only `claim`,
   *  `once` and `unguarded` make one; it is invariant in `K`, so the evidence for one key is not evidence for
   *  another. One instance serves every key: a holder allocates nothing.
   *
   *  The writer's state is private, its writes behind methods that take the evidence (dotc's `LazyRef`):
   *  {{{
   *  private def set(ref: Type | Null, done: Boolean)(using Sharing.Holds[this.type]): Unit =
   *    myRef = ref
   *    computed = done
   *  }}}
   *  and `set(...)` outside `Sharing.claim(this)` or `Sharing.once(this)` is an error: "this code must run as the one
   *  writer of (LazyRef.this : LazyRef)". At run time the parameter is the one shared `held` object.
   */
  @implicitNotFound("this code must run as the one writer of ${K}: inside Sharing.claim or Sharing.once on it, or a completion of it")
  sealed abstract class Holds[K]
  private object held extends Holds[Any]

  /** Evidence of `claim(key)` without it, for an access that races on purpose, or a claim taken elsewhere (dotc's
   *  completions run under their root's claim); `why` says why it is safe (Clang's `NO_THREAD_SAFETY_ANALYSIS`,
   *  scoped to one access). `K` is inferred from where the evidence is used:
   *  {{{
   *  // BaseDataImpl: the dependent cache's computation holds the claim further up this thread's stack
   *  setProvisional(true)(using Sharing.unguarded("signalled from inside the claim's own computation"))
   *  }}}
   *  Expands to the shared evidence object; `why` is not evaluated, it is there for the reader (and the state map).
   */
  inline def unguarded[K](inline why: String): Holds[K] = held.asInstanceOf[Holds[K]]

  /** Evidence that the code it is given to holds the monitor of `lock: K` (in a concurrent mode; in batch mode it
   *  runs alone), as `Holds` is of a claim: state a monitor guards is reached only through methods that take one.
   *  Made by `serialised`, `locking`, a `Guard`'s `locked` and `alwaysLocked`, and `unlocked`; a claim's evidence is
   *  not a monitor's, nor the reverse. A `Guarded` field takes it to be read: `bySource()` outside `locked` is an
   *  error, "this code must hold the monitor of (Run.this.suppressions : ...)".
   */
  @implicitNotFound("this code must hold the monitor of ${K}: inside its `locked` or `alwaysLocked`, if it is a Sharing.Guard, or Sharing.serialised or Sharing.locking on it")
  sealed abstract class Monitor[K]
  private object monitorHeld extends Monitor[Any]

  /** Evidence of `lock`'s monitor without it, for an access that races on purpose; `why` says why it is safe:
   *  {{{
   *  // ContextDocstrings: the pickler reads the map after the typer, when no reader writes it
   *  def docstrings: ReadOnlyMap[Symbol, Comment] =
   *    _docstrings()(using Sharing.unlocked("read after the typer, or by the driver's edits with no reader"))
   *  }}}
   *  Bind it to a value, not a `given` alias, if it is used more than once: `given M = Sharing.unlocked(...)` is a
   *  lazy given, a `LazyRef` per call; `val m = Sharing.unlocked(...); given M = m` is not.
   */
  inline def unlocked[K](inline why: String): Monitor[K] = monitorHeld.asInstanceOf[Monitor[K]]

  /** How the claim on a key of type `K` is taken (a given for each kind of key, beside its class): what its holder
   *  is doing, for lock-wait reports, and whether serialised threads take the coarse lock for it (dotc: as for a
   *  completion: a bring-forward, an inherited cache, a change to a published scope, a lazy TASTy read), or need no
   *  claim, since their reads are serialised and the computation is not a completion (a lazy reference).
   *  {{{
   *  object LazyRef:
   *    given Sharing.ClaimKind[LazyRef] = Sharing.ClaimKind(CompletionLock.lazyRef, lockWhenSerialised = false)
   *  }}}
   *  Contravariant, so one given serves a class, its subclasses and the singleton types `claim` infers
   *  (`ClaimKind[LazyRef] <: ClaimKind[r.type]`), and one for a union serves both (`ClaimKind[LazyAnnotation |
   *  LazyBodyAnnotation]`). The given is found through the key's class (its companion or enclosing object), so a
   *  key with no kind is an error at the claim: "no Sharing.ClaimKind for ...".
   */
  @implicitNotFound("no Sharing.ClaimKind for ${K}: a key's class says how it is claimed with a given ClaimKind beside it")
  final class ClaimKind[-K](val what: What, val lockWhenSerialised: Boolean)

  /** `op` as the one writer of the lazy facts keyed by `key`, with the evidence of it: in a concurrent mode through
   *  the `ClaimRegistry` (the key's claim when parallel, where a wait that would close a wait-for cycle is reported
   *  against `cycleSubject`; the coarse lock, or as is, when serialised, as the key's `ClaimKind` says); as is in
   *  batch mode. `key`, its kind, `cycleSubject` and the registry are evaluated only in a concurrent mode.
   *  `cycleSubject` defaults to the key.
   *  {{{
   *  // MutableScope.writing: the scope's one writer
   *  Sharing.claim(this)(inside(op))
   *  // SingleDenotation.current: one key for every bring-forward, the cycle reported against the symbol
   *  Sharing.claim(BringForward, symbol)(initial.bringForward().current)
   *  }}}
   *  The key is a stable path (`this`, a val, an object), whose singleton type is inferred as `K`. A call expands to
   *  {{{
   *  val h = held.asInstanceOf[Holds[K]]
   *  if mode.concurrent then registry.claim(key, kind, cycleSubject, () => op(using h))
   *  else op(using h)
   *  }}}
   *  Re-entry by the owner of a claim runs `op` at once: the owner's test for re-entering its own computation is
   *  `op`'s (a `BlackHole`, or a sentinel in the state).
   */
  inline def claim[K <: AnyRef & Singleton, T](inline key: K, inline cycleSubject: AnyRef | Null = null)(
      inline op: Holds[K] ?=> T)(using inline kind: ClaimKind[K])(using inline mode: Mode, inline registry: ClaimRegistry): T =
    // the evidence a local value, not a given alias, which would be a lazy one: a `scala.runtime.LazyRef` per call
    val h = held.asInstanceOf[Holds[K]]
    if mode.concurrent then registry.claim(key, kind, if cycleSubject == null then key else cycleSubject.nn, () => op(using h))
    else op(using h)

  /** `claim` on a key computed per call (a file's root, found from its symbols), which no state is reached by: it
   *  hands out no evidence, and `key` is evaluated only in a concurrent mode, as in `claim`. The kind is given
   *  explicitly, since the key has no class to find it through:
   *  {{{
   *  // TreeUnpickler.unpickle: the file's address maps, under the claim its completers take
   *  Sharing.claimAt(claimKey, Sharing.lazyTastyRead, this)(unpickle0(mode))
   *  }}}
   *  Expands to `if mode.concurrent then registry.claim(key, kind, cycleSubject, () => op) else op`.
   */
  inline def claimAt[T](inline key: AnyRef, inline kind: ClaimKind[Nothing], inline cycleSubject: AnyRef)(inline op: T)(
      using inline mode: Mode, inline registry: ClaimRegistry): T =
    if mode.concurrent then registry.claim(key, kind, cycleSubject, () => op) else op

  /** A lazy fact keyed by `key`, computed once: the value `peek` finds, or, when it finds none, `compute`d as the
   *  one writer of `key` (`claim`), with `peek` asked again inside first, since another thread may have computed it
   *  meanwhile. `peek` must find the value only once it is complete; `compute` stores it. Another thread waits for an
   *  owner's computation at the claim, so `compute` is entered again only by its owner, re-entering its own
   *  computation: that is the cycle test's place. In batch mode `peek ?: compute`.
   *  {{{
   *  // LazyRef.ref: `myRef` is null until the value is stored; `computed` is set when the computation starts
   *  def ref(using Context): Type =
   *    Sharing.once(this)(myRef):
   *      if computed then throw CyclicReference(NoDenotation) // only the owner gets here again
   *      compute()
   *      myRef.nn
   *  }}}
   *  Expands to the double-checked computation
   *  {{{
   *  val v = peek
   *  if v != null then v
   *  else claim(key, cycleSubject):
   *    val v1 = peek
   *    if v1 != null then v1 else compute
   *  }}}
   *  so a hit is `peek`'s reads and a null test, in every mode. `peek` reads without the claim: what it reads must
   *  be published whole (a final-field structure, a volatile, a value stored after what it refers to).
   */
  inline def once[K <: AnyRef & Singleton, T <: AnyRef](inline key: K, inline cycleSubject: AnyRef | Null = null)(
      inline peek: T | Null)(inline compute: Holds[K] ?=> T)(using inline kind: ClaimKind[K])(
      using inline mode: Mode, inline registry: ClaimRegistry): T =
    val v = peek
    if v != null then v.asInstanceOf[T]
    else claim(key, cycleSubject):
      val v1 = peek
      if v1 != null then v1.asInstanceOf[T] else compute

  /** The black hole of a computation that the holder of the claim on this object runs: which thread is inside it.
   *  Entered and left only with the claim's evidence; whether this thread is inside needs none, since only its own
   *  store can make that true. A field of the object it is mixed into, so it allocates nothing.
   *  {{{
   *  private abstract class InheritedCacheImpl(...) extends InheritedCache, Sharing.BlackHole
   *  // BaseDataImpl.apply, inside `Sharing.claim(this)`: a re-entry by the owner is a cycle
   *  val computed = computeInside(throw CyclicReference(clsd))(clsd.computeBaseData(using this, ctx))
   *  // BaseDataImpl.invalidate, from any thread: an invalidation from inside the computation is ignored
   *  if valid && !isInsideHere then ...
   *  }}}
   *  `inside(op)` expands to `val was = insideThread; insideThread = currentThread; try op finally insideThread = was`,
   *  and `computeInside(onCycle)(op)` to `if insideThread != null then onCycle else inside(op)`.
   */
  trait BlackHole:
    @benignRace("written under the claim; read without it only to compare with this thread")
    private var insideThread: Thread | Null = null
    /** `op` with this thread inside, restoring who was inside before (a nested entry by the same thread). */
    protected final inline def inside[T](inline op: T)(using Holds[this.type]): T =
      val was = insideThread
      insideThread = Thread.currentThread
      try op finally insideThread = was
    /** `op` as the one computation this black hole guards: entered by the holder of the claim, the only thread that
     *  can be inside, so finding some thread inside is the holder re-entering its own computation, a cycle,
     *  `onCycle`. However `op` ends, it leaves: a computation that unwinds (a cancellation, a back-off, a crash) leaves
     *  what it guards computable by the next caller, which a hand-written "started" flag must remember to do (I97).
     */
    protected final inline def computeInside[T](inline onCycle: => Nothing)(inline op: T)(using Holds[this.type]): T =
      if insideThread != null then onCycle else inside(op)
    /** Is some thread inside? */
    protected final def isInside(using Holds[this.type]): Boolean = insideThread != null
    /** Is this thread inside? */
    protected final def isInsideHere: Boolean = insideThread eq Thread.currentThread

  /** `op` holding `lock`'s monitor in a concurrent mode, as is otherwise, with the evidence of it. For state that
   *  lives in `lock` but is not a `Guard`'s (a trait's fields, a map another object owns):
   *  {{{
   *  // ConstraintRunInfo: a statistic every reader reports to
   *  Sharing.serialised(this) {
   *    if size > maxSize then
   *      maxSize = size
   *      maxConstraint = c
   *  }
   *  }}}
   *  Expands to `if mode.concurrent then lock.synchronized(op(using m)) else op(using m)`, `m` the shared evidence
   *  object. Prefer a `Guard` where the state can move into one: then nothing reaches it without the monitor.
   */
  inline def serialised[K <: AnyRef & Singleton, T](lock: K)(inline op: Monitor[K] ?=> T)(using inline mode: Mode): T =
    val m = monitorHeld.asInstanceOf[Monitor[K]] // not a given alias, as in `claim`
    if mode.concurrent then lock.synchronized(op(using m)) else op(using m)

  /** `op` holding `lock`'s monitor in every mode, with the evidence of it: `serialised` for code with no mode, or
   *  state that more than the threads of one owner reach. Expands to `lock.synchronized(op(using m))`.
   */
  inline def locking[K <: AnyRef & Singleton, T](lock: K)(inline op: Monitor[K] ?=> T): T =
    lock.synchronized(op(using monitorHeld.asInstanceOf[Monitor[K]]))

  /** A variable of a `Guard` reached only with the evidence of the monitor of `K`, the guard: `v()` and `v() = x`.
   *  {{{
   *  object suspension extends Sharing.Guard:
   *    private val atTyper = guardedVar(false)
   *    def add(...) = locked { ...; atTyper() = true }
   *    def atTyperPhase(using Context): Boolean = locked(atTyper())
   *  }}}
   *  A box, one object per variable, read and written through it; a `Guarded` value costs nothing, so prefer a
   *  guarded collection to a guarded variable where either serves.
   */
  final class GuardedVar[K, T](private var value: T):
    inline def apply()(using Monitor[K]): T = value
    inline def update(v: T)(using Monitor[K]): Unit = value = v

  /** An object whose monitor guards the state it makes with `guarded` and `guardedVar`: the state lives in the
   *  guard, and is reached only inside `locked` (in a concurrent mode; batch mode runs alone) or `alwaysLocked`
   *  (in every mode, for code without one).
   *  {{{
   *  object suppressions extends Sharing.Guard:
   *    private val bySource = guarded(mutable.LinkedHashMap.empty[SourceFile, ListBuffer[Suppression]])
   *    def addSuppression(sup: Suppression)(using Context): Unit =
   *      locked:
   *        bySource().getOrElseUpdate(sup.annotPos.source, ListBuffer.empty) += sup
   *  }}}
   *  `locked(op)` expands to `serialised(this)(op)`, `alwaysLocked(op)` to `locking(this)(op)`, and `bySource()` to
   *  the field read. What `op` returns is outside the monitor: return a copy (`units().toList`), not the collection.
   */
  abstract class Guard:
    protected final def guarded[T](init: T): Guarded[this.type, T] = Guarded.wrap(init)
    protected final def guardedVar[T](init: T): GuardedVar[this.type, T] = GuardedVar(init)
    final inline def locked[T](inline op: Monitor[this.type] ?=> T)(using inline mode: Mode): T =
      serialised(this)(op)
    final inline def alwaysLocked[T](inline op: Monitor[this.type] ?=> T): T =
      locking(this)(op)

/** A value of a `Sharing.Guard` reached only with the evidence of the monitor of `K`, the guard: `g()` takes it. The
 *  value itself, so a field of this type costs what the field did, and `g()` expands to the field read. Made by the
 *  guard's `guarded(init)`, its type `Guarded[guard.type, T]` (see `Sharing.Guard` for a use). Outside
 *  `object Sharing`: an opaque type is seen through in the result types of the members of classes defined in its
 *  scope, `Guard.guarded`'s among them.
 */
opaque type Guarded[K, T] = T
object Guarded:
  private[sharing] inline def wrap[K, T](t: T): Guarded[K, T] = t
  extension [K, T](g: Guarded[K, T]) inline def apply()(using Sharing.Monitor[K]): T = g
