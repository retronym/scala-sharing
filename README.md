# scala-sharing

Concurrency primitives for state shared between threads, for Scala 3. They come from the Scala 3 compiler's work on several readers over one symbol table ([retronym/scala3#84](https://github.com/retronym/scala3/pull/84)) and do not depend on the compiler.

Docs and API: <https://retronym.github.io/scala-sharing/docs/index.html>. Package: `io.github.retronym.sharing`.

Two ideas run through it:

- **Batch mode costs nothing.** The owner of the state picks a [`Mode`][mode] once. In [`Mode.Batch`][mode] every operation is the plain code it wraps: an `inline def` that compiles to a test of a final field and the original expression, with no lock and no allocation. Code written against the library runs single-threaded at its old speed. One exception, by design: [`Published`][published], [`Payload`][payload] and `Publish` keep their release and acquire accesses in every mode, a cost to batch deemed low enough to pay unconditionally.
- **Guarded state is reachable only with evidence.** A claim (a reentrant lock per key, whose waits feed a wait-for graph for deadlock detection) or a monitor hands its code a phantom-typed evidence parameter ([`Holds[key.type]`][holds], [`Monitor[lock.type]`][monitor]), and the guarded state is reachable only through methods that take one. Touching it outside the claim or lock is a compile error, not a race. At run time the evidence is one shared object.

## Highlights

### A lazy fact, computed once by one writer

<div class="tabs">
<input type="radio" name="once" id="once-0" checked><label for="once-0"><strong>Source</strong></label>
<div class="tab">

```scala
import io.github.retronym.sharing.*

class Symbol(name: String)(using Mode, ClaimRegistry):
  private[mypkg] var myInfo: Once[this.type, Info] = Once.empty

  // read; on a miss, claim `this`, read again, compute and store
  def info: Info = myInfo.get(computeInfo())

object Symbol:
  given Sharing.ClaimKind[Symbol] = Sharing.ClaimKind(What("computing info", null), lockWhenSerialised = true)
  val myInfoHandle: Published.Handle[Symbol, "myInfo"] = Published.handle
  given Published.Handle[Symbol, "myInfo"] = myInfoHandle
```

</div>
<input type="radio" name="once" id="once-1"><label for="once-1"><strong>Compiled</strong></label>
<div class="tab scroll">

```scala
def info: Info =
  if mode.concurrent then                       // mode: the owner's given, a final field
    val v = Symbol.myInfoHandle.getAcquire(this)
    if v != null then v
    else registry.claim(this, Symbol.given_ClaimKind_Symbol, this, () =>
      val v1 = this.myInfo
      if v1 != null then v1
      else
        val r = computeInfo()
        Symbol.myInfoHandle.setRelease(this, r)
        r
    )
  else
    val v = this.myInfo
    if v != null then v
    else
      val r = computeInfo()
      this.myInfo = r
      r
```

</div>
<input type="radio" name="once" id="once-2"><label for="once-2"><strong>Batch path</strong></label>
<div class="tab">

```scala
def info: Info =
  val v = this.myInfo
  if v != null then v
  else
    val r = computeInfo()
    this.myInfo = r
    r
```

</div>
<input type="radio" name="once" id="once-3"><label for="once-3"><strong>Concurrent path</strong></label>
<div class="tab">

```scala
def info: Info =
  val v = Symbol.myInfoHandle.getAcquire(this)
  if v != null then v
  else registry.claim(this, Symbol.given_ClaimKind_Symbol, this, () =>
    val v1 = this.myInfo                        // plain: the claim orders it
    if v1 != null then v1
    else
      val r = computeInfo()
      Symbol.myInfoHandle.setRelease(this, r)
      r
  )

// registry.claim, by the registry chosen with the mode:
//   CoarseLock   : lock(); try op() finally unlock()  (a ReentrantLock; op()
//                  alone if the ClaimKind says lockWhenSerialised = false)
//   PerKeyClaims : putIfAbsent a claim on `this` and run op(), or park on
//                  the holder's latch, checking the wait-for graph each 5 ms
```

</div>
</div>

`Mode` is a runtime value, the owner's given, so the Compiled tab is what every call site holds: both paths behind a test of `mode.concurrent`, a field load and a branch. The path tabs show each side alone. C2 profiles the branch, so in a process that only ever runs one mode the other path is left out of the compiled method (an uncommon trap, recompiled if it is ever taken), and the hot path is that mode's code and one predictable test. The cost of carrying both is bytecode size, which counts against C2's inlining budget at each call site.

The lock discipline is the field's type, not the class's: a plain read or store of `myInfo` does not compile, and the library makes the only store, inside the claim on the owner, after `compute` returns. In [`Mode.Concurrent`][mode] the store is a release and the read an acquire, so the value is safely published however it was built; in [`Mode.Batch`][mode] both are plain, and a hit is the field read and a null test. `compute` is given [`Sharing.Holds[this.type]`][holds], the evidence for the owner's other writer state. [`Sharing.BlackHole`][blackhole] adds re-entry detection: a computation entered again by its own owner is a cycle, and one that unwinds leaves the fact computable. A fact keyed by another object, or that is not one reference, uses [`Sharing.once`][sharing] or [`Sharing.claim`][sharing], with the store written by the owner behind a method that takes [`Holds`][holds].

Compared with a Scala 3 `lazy val`, which is also computed once, thread-safely, and left computable if its initialiser throws: a `lazy val` pays for thread safety in every instance (or never, with `@threadUnsafe`), where [`Once`][once] follows the owner's [`Mode`][mode], plain in batch. Its waiters block on the initialising thread with no wait-for graph, so two initialisers that need each other's values on two threads deadlock, where `Once` waits through the [`ClaimRegistry`][claimregistry], which detects the cycle, aborts a victim and reports what each holder was doing. It cannot be reset, where `Once` has `reset()` for invalidation, under the claim. And its initialiser is given no evidence, where `compute` is given [`Holds[this.type]`][holds] for the owner's other writer state. The `lazy val` costs no boilerplate and can hold `null`; `Once` needs a handle and a [`ClaimKind`][claimkind] beside the class, and uses `null` for "not yet".

### State behind a monitor

<div class="tabs">
<input type="radio" name="guard" id="guard-0" checked><label for="guard-0"><strong>Source</strong></label>
<div class="tab">

```scala
object registry extends Sharing.Guard:
  private val byName = guarded(mutable.HashMap.empty[String, Entry])
  def add(e: Entry)(using Mode): Unit = locked(byName()(e.name) = e)
  def names(using Mode): List[String] = locked(byName().keys.toList)
```

</div>
<input type="radio" name="guard" id="guard-1"><label for="guard-1"><strong>Compiled</strong></label>
<div class="tab scroll">

```scala
object registry extends Sharing.Guard:
  private val byName = mutable.HashMap.empty[String, Entry]   // Guarded erases to the map
  def add(e: Entry)(using mode: Mode): Unit =
    if mode.concurrent then registry.synchronized { byName(e.name) = e }
    else byName(e.name) = e
  def names(using mode: Mode): List[String] =
    if mode.concurrent then registry.synchronized { byName.keys.toList }
    else byName.keys.toList
```

</div>
<input type="radio" name="guard" id="guard-2"><label for="guard-2"><strong>Batch path</strong></label>
<div class="tab">

```scala
object registry extends Sharing.Guard:
  private val byName = mutable.HashMap.empty[String, Entry]   // Guarded erases to the map
  def add(e: Entry)(using Mode): Unit = byName(e.name) = e
  def names(using Mode): List[String] = byName.keys.toList
```

</div>
<input type="radio" name="guard" id="guard-3"><label for="guard-3"><strong>Concurrent path</strong></label>
<div class="tab">

```scala
object registry extends Sharing.Guard:
  private val byName = mutable.HashMap.empty[String, Entry]
  def add(e: Entry)(using Mode): Unit = registry.synchronized { byName(e.name) = e }
  def names(using Mode): List[String] = registry.synchronized { byName.keys.toList }
```

</div>
</div>

`byName()` outside `locked` does not compile. [`Guarded`][guarded] is an opaque type over the value itself, so the field costs what it did.

The lock is the guard object's own monitor: `locked(op)` is `registry.synchronized(op)` in [`Mode.Concurrent`][mode] and `op` in [`Mode.Batch`][mode], and `alwaysLocked` takes it in every mode. [`Sharing.serialised(obj)`][sharing] and [`Sharing.locking(obj)`][sharing] do the same on another object's monitor, for state that cannot move into a [`Guard`][guard]. The monitor is reentrant, so a `locked` block may call another method of the same guard.

The evidence proves the monitor is held when the state is touched; it does not prevent deadlock, which is the caller's to avoid:

- **Keep a locked block a leaf.** Do not call out of it into code that may take another lock: a callback, a listener, another guard's `locked`, blocking I/O. Two threads that each hold one monitor and want the other's deadlock, and nothing here sees it.
- **Never take a claim, or compute a [`Once`][once], inside a locked block.** A claim may wait for another thread's computation, which may need this monitor. Monitor waits are not in the wait-for graph, so [`PerKeyClaims`][perkeyclaims] cannot detect that cycle and abort a victim. The reverse is fine: a short locked block inside a claim.
- **If monitors must nest, take them in one global order.**
- **Do not let the guarded value escape.** `locked(byName())` returns the mutable map itself, and the type allows it; return a copy or an immutable view, as `names` does with `toList`.
- **The monitor is public.** Code outside that synchronises on the guard object takes the same lock, so keep the guard private, or treat the object as the lock and nothing else.

### Release/acquire publishing, checked at compile time

<div class="tabs">
<input type="radio" name="published" id="published-0" checked><label for="published-0"><strong>Source</strong></label>
<div class="tab">

```scala
class Entry:
  private[mypkg] var version: Published[Int] = Published(0)   // not private: see Published.handle
  private var text: Payload[Entry, "version", String] = Payload("")

  def read(expected: Int): String | Null =
    val snap = version.acquired                 // getAcquire
    if snap.value == expected then text.after(snap) else null

  def write(v: Int, t: String): Unit =
    version.publishing(v):                      // stores, then setRelease
      text := t

object Entry:
  val versionHandle: Published.Handle[Entry, "version"] = Published.handle
  given Published.Handle[Entry, "version"] = versionHandle
```

</div>
<input type="radio" name="published" id="published-1"><label for="published-1"><strong>Compiled, in every mode</strong></label>
<div class="tab">

```scala
class Entry:
  var version: Int = 0
  private var text: String = ""

  def read(expected: Int): String | Null =
    val snap = Entry.versionHandle.getAcquire(this): Int
    if snap == expected then this.text else null

  def write(v: Int, t: String): Unit =
    this.text = t
    Entry.versionHandle.setRelease(this, v)

object Entry:
  val versionHandle: VarHandle =
    MethodHandles.privateLookupIn(classOf[Entry], MethodHandles.lookup())
      .findVarHandle(classOf[Entry], "version", classOf[Int])
```

</div>
</div>

Macros over a `VarHandle` that C2 folds. The accesses take no [`Mode`][mode]: in batch they are still release and acquire, which costs nothing on x86 beyond limiting how the JIT reorders around them, and an `ldar`/`stlr` on ARM, deemed low enough to do unconditionally. A plain read or store does not type-check. A payload stored outside its publisher's block, read after another object's acquire, or with the block's evidence leaked, is a compile error.

### A value with the stamp it was computed at

<div class="tabs">
<input type="radio" name="stamped" id="stamped-0" checked><label for="stamped-0"><strong>Source</strong></label>
<div class="tab">

```scala
private var exports: Stamped[PerGeneration, List[String]] = Stamped.Empty
def exportedNames(using Stamp[PerGeneration]): List[String] =
  exports.cached(computeExports())
```

</div>
<input type="radio" name="stamped" id="stamped-1"><label for="stamped-1"><strong>Compiled, in every mode</strong></label>
<div class="tab">

```scala
private var exports: Stamped[PerGeneration, List[String]] = Stamped.Empty
def exportedNames(using now: Stamp[PerGeneration]): List[String] =
  val n = now.value
  val c = this.exports                          // one plain load: final fields make it safe
  if c.stamp == n then c.value
  else
    val v = computeExports()
    this.exports = new Stamped(n, v)
    v
```

</div>
</div>

One immutable object in one field, so a reader sees the stamp and value whole with no fences. The stamp's kind is a phantom type, so a field is never read with one kind of stamp and written with another.

### Serialised or parallel, chosen once

```scala
val concurrency = Concurrency.Parallel        // or Serialised, or Batch
given Mode = concurrency.mode
given ClaimRegistry = concurrency.newRegistry(() => claimantOfThisThread())
```

[`CoarseLock`][coarselock] serialises the threads under one reentrant lock and records what its holder is doing for lock-wait reports. [`PerKeyClaims`][perkeyclaims] gives each key its own claim, detects wait-for cycles between claimants, and aborts one party as the deadlock victim ([`Cancelled`][cancelled], and its work retried) or has one report the cycle.

## Contents

- [`Sharing`][sharing]: [`once`][sharing], [`claim`][sharing], [`claimAt`][sharing], [`Holds`][holds], [`unguarded`][sharing]; [`Guard`][guard], [`Guarded`][guarded], [`GuardedVar`][guardedvar], [`serialised`][sharing], [`locking`][sharing], [`Monitor`][monitor], [`unlocked`][sharing]; [`ClaimKind`][claimkind]; [`BlackHole`][blackhole].
- [`Once`][once]: a lazy fact in one field, stored only by the library.
- [`Published`][published], [`Payload`][payload]: release/acquire fields. `Publish`: release/acquire array slots.
- [`Stamped`][stamped], [`Stamp`][stamp].
- [`ClaimRegistry`][claimregistry], [`CoarseLock`][coarselock], [`PerKeyClaims`][perkeyclaims], [`Claimant`][claimant], [`CycleHandler`][cyclehandler], [`Cancelled`][cancelled], [`What`][what], [`Label`][label].
- [`Mode`][mode], [`Concurrency`][concurrency], [`benignRace`][benignrace].

The tests are Lincheck model-checking and stress suites, one section per promise, including a toy compiler symbol table and a wait-for cycle, and compile-time checks that misuse of [`Published`][published] and [`Once`][once] is rejected.

## Related work

The evidence types are capabilities in phantom types. That idea has relatives in several languages.

- **Clang Thread Safety Analysis.** `GUARDED_BY(mu)` on data, `REQUIRES(mu)` on functions, `ACQUIRE`/`RELEASE` on lock operations, and `NO_THREAD_SAFETY_ANALYSIS` to opt out. [`Holds`][holds]/[`Monitor`][monitor] play the part of `REQUIRES` and [`unguarded`][sharing]/[`unlocked`][sharing] the part of the opt-out, scoped to one access with a reason. Clang checks annotations in a separate analysis; here the type checker does it, because the capability is a parameter.
- **Rust.** `Mutex<T>` puts the data inside the lock so it is reachable only through a `MutexGuard`; [`Guard`][guard]/[`Guarded`][guarded] is that shape. `Send` and `Sync` are marker traits for what may cross or be shared between threads. `OnceLock`/`LazyLock` are the single-writer lazy value of [`once`][sharing]; [`Once`][once] is `OnceLock` without the box, the field itself made opaque. GhostCell and the `qcell` crate separate permission from data with a branded token (a lifetime or marker type), as [`Holds[key.type]`][holds] brands evidence with a singleton type. Salsa (behind rust-analyzer) stamps derived values with a revision and detects query cycles, as [`Stamped`][stamped] and [`BlackHole`][blackhole] do.
- **Haskell.** The `ST` monad's rank-2 phantom `s` keeps an `STRef` from escaping its computation, as a singleton-typed `Holds` keeps evidence for one key from serving another. Kiselyov and Shan's *Lightweight Static Capabilities* and Noonan's *Ghosts of Departed Proofs* use phantom-typed names and proofs to carry facts the type checker enforces, which is the role of `Holds`, `Monitor` and [`Published.Acquired`][published-obj]. `MVar` and STM's `TVar` are runtime alternatives.
- **Java.** Error Prone's `@GuardedBy` and `@LazyInit` (the source of [`benignRace`][benignrace]), and the Checker Framework's Lock Checker. `VarHandle` access modes are what [`Published`][published], `Once` and `Publish` compile to.
- **Scala.** Capture checking (`-language:experimental.captureChecking`) tracks capabilities in types too, at the level of references rather than one parameter per operation.

## Build

The README's tabbed panels show what each example compiles to in each mode; `sbt printExpansions` prints the compiler's own expansion (`-Vprint:inlining`) to check them against when a macro changes.

```
sbt test
sbt doc
```

JDK 17 or later.

## License

Apache 2.0. Derived from the Scala 3 compiler; see `NOTICE`.

[benignrace]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/benignRace.html
[blackhole]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Sharing$$BlackHole.html
[cancelled]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Cancelled.html
[claimant]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Claimant.html
[claimkind]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Sharing$$ClaimKind.html
[claimregistry]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/ClaimRegistry.html
[coarselock]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/CoarseLock.html
[concurrency]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Concurrency.html
[cyclehandler]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/CycleHandler.html
[guard]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Sharing$$Guard.html
[guarded]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing.html#Guarded-0
[guardedvar]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Sharing$$GuardedVar.html
[holds]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Sharing$$Holds.html
[label]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Label.html
[mode]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Mode.html
[monitor]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Sharing$$Monitor.html
[once]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing.html#Once-0
[payload]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing.html#Payload-0
[perkeyclaims]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/PerKeyClaims.html
[published]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing.html#Published-0
[published-obj]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Published$.html
[sharing]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Sharing$.html
[stamp]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Stamp.html
[stamped]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/Stamped.html
[what]: https://retronym.github.io/scala-sharing/io/github/retronym/sharing/What.html
