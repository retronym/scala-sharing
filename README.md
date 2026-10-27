# scala-sharing

Concurrency primitives for state shared between threads, for Scala 3. They come from the Scala 3 compiler's work on several readers over one symbol table ([retronym/scala3#84](https://github.com/retronym/scala3/pull/84)) and do not depend on the compiler.

Docs and API: <https://retronym.github.io/scala-sharing/docs/index.html>. Package: `io.github.retronym.sharing`.

Two ideas run through it:

- **Batch mode costs nothing.** The owner of the state picks a `Mode` once. In `Mode.Batch` every operation is the plain code it wraps: an `inline def` that compiles to a test of a final field and the original expression, with no lock and no allocation. Code written against the library runs single-threaded at its old speed.
- **Guarded state is reachable only with evidence.** A claim (a reentrant lock per key, whose waits feed a wait-for graph for deadlock detection) or a monitor hands its code a phantom-typed evidence parameter (`Holds[key.type]`, `Monitor[lock.type]`), and the guarded state is reachable only through methods that take one. Touching it outside the claim or lock is a compile error, not a race. At run time the evidence is one shared object.

## Highlights

### A lazy fact, computed once by one writer

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

The lock discipline is the field's type, not the class's: a plain read or store of `myInfo` does not compile, and the library makes the only store, inside the claim on the owner, after `compute` returns. In `Mode.Concurrent` the store is a release and the read an acquire, so the value is seen whole however it was built; in `Mode.Batch` both are plain, and a hit is the field read and a null test. `compute` is given `Sharing.Holds[this.type]`, the evidence for the owner's other writer state. `Sharing.BlackHole` adds re-entry detection: a computation entered again by its own owner is a cycle, and one that unwinds leaves the fact computable. A fact keyed by another object, or that is not one reference, uses `Sharing.once` or `Sharing.claim`, with the store written by the owner behind a method that takes `Holds`.

Compared with a Scala 3 `lazy val`, which is also computed once, thread-safely, and left computable if its initialiser throws: a `lazy val` pays for thread safety in every instance (or never, with `@threadUnsafe`), where `Once` follows the owner's `Mode`, plain in batch. Its waiters block on the initialising thread with no wait-for graph, so two initialisers that need each other's values on two threads deadlock, where `Once` waits through the `ClaimRegistry`, which detects the cycle, aborts a victim and reports what each holder was doing. It cannot be reset, where `Once` has `reset()` for invalidation, under the claim. And its initialiser is given no evidence, where `compute` is given `Holds[this.type]` for the owner's other writer state. The `lazy val` costs no boilerplate and can hold `null`; `Once` needs a handle and a `ClaimKind` beside the class, and uses `null` for "not yet".

### State behind a monitor

```scala
object registry extends Sharing.Guard:
  private val byName = guarded(mutable.HashMap.empty[String, Entry])
  def add(e: Entry)(using Mode): Unit = locked(byName()(e.name) = e)
  def names(using Mode): List[String] = locked(byName().keys.toList)
```

`byName()` outside `locked` does not compile. `Guarded` is an opaque type over the value itself, so the field costs what it did.

The lock is the guard object's own monitor: `locked(op)` is `registry.synchronized(op)` in `Mode.Concurrent` and `op` in `Mode.Batch`, and `alwaysLocked` takes it in every mode. `Sharing.serialised(obj)` and `Sharing.locking(obj)` do the same on another object's monitor, for state that cannot move into a `Guard`. The monitor is reentrant, so a `locked` block may call another method of the same guard.

The evidence proves the monitor is held when the state is touched; it does not prevent deadlock, which is the caller's to avoid:

- **Keep a locked block a leaf.** Do not call out of it into code that may take another lock: a callback, a listener, another guard's `locked`, blocking I/O. Two threads that each hold one monitor and want the other's deadlock, and nothing here sees it.
- **Never take a claim, or compute a `Once`, inside a locked block.** A claim may wait for another thread's computation, which may need this monitor. Monitor waits are not in the wait-for graph, so `PerKeyClaims` cannot detect that cycle and abort a victim. The reverse is fine: a short locked block inside a claim.
- **If monitors must nest, take them in one global order.**
- **Do not let the guarded value escape.** `locked(byName())` returns the mutable map itself, and the type allows it; return a copy or an immutable view, as `names` does with `toList`.
- **The monitor is public.** Code outside that synchronises on the guard object takes the same lock, so keep the guard private, or treat the object as the lock and nothing else.

### Release/acquire publishing, checked at compile time

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

Macros over a `VarHandle` that C2 folds. A plain read or store does not type-check. A payload stored outside its publisher's block, read after another object's acquire, or with the block's evidence leaked, is a compile error.

### A value with the stamp it was computed at

```scala
private var exports: Stamped[PerGeneration, List[String]] = Stamped.Empty
def exportedNames(using Stamp[PerGeneration]): List[String] =
  exports.cached(computeExports())
```

One immutable object in one field, so a reader sees the stamp and value whole with no fences. The stamp's kind is a phantom type, so a field is never read with one kind of stamp and written with another.

### Serialised or parallel, chosen once

```scala
val concurrency = Concurrency.Parallel        // or Serialised, or Batch
given Mode = concurrency.mode
given ClaimRegistry = concurrency.newRegistry(() => claimantOfThisThread())
```

`CoarseLock` serialises the threads under one reentrant lock and records what its holder is doing for lock-wait reports. `PerKeyClaims` gives each key its own claim, detects wait-for cycles between claimants, and aborts one party as the deadlock victim (`Cancelled`, and its work retried) or has one report the cycle.

## Contents

- `Sharing`: `once`, `claim`, `claimAt`, `Holds`, `unguarded`; `Guard`, `Guarded`, `GuardedVar`, `serialised`, `locking`, `Monitor`, `unlocked`; `ClaimKind`; `BlackHole`.
- `Once`: a lazy fact in one field, stored only by the library.
- `Published`, `Payload`: release/acquire fields. `Publish`: release/acquire array slots.
- `Stamped`, `Stamp`.
- `ClaimRegistry`, `CoarseLock`, `PerKeyClaims`, `Claimant`, `CycleHandler`, `Cancelled`, `What`, `Label`.
- `Mode`, `Concurrency`, `benignRace`.

The tests are Lincheck model-checking and stress suites, one section per promise, including a toy compiler symbol table and a wait-for cycle, and compile-time checks that misuse of `Published` and `Once` is rejected.

## Related work

The evidence types are capabilities in phantom types. That idea has relatives in several languages.

- **Clang Thread Safety Analysis.** `GUARDED_BY(mu)` on data, `REQUIRES(mu)` on functions, `ACQUIRE`/`RELEASE` on lock operations, and `NO_THREAD_SAFETY_ANALYSIS` to opt out. `Holds`/`Monitor` play the part of `REQUIRES` and `unguarded`/`unlocked` the part of the opt-out, scoped to one access with a reason. Clang checks annotations in a separate analysis; here the type checker does it, because the capability is a parameter.
- **Rust.** `Mutex<T>` puts the data inside the lock so it is reachable only through a `MutexGuard`; `Guard`/`Guarded` is that shape. `Send` and `Sync` are marker traits for what may cross or be shared between threads. `OnceLock`/`LazyLock` are the single-writer lazy value of `once`; `Once` is `OnceLock` without the box, the field itself made opaque. GhostCell and the `qcell` crate separate permission from data with a branded token (a lifetime or marker type), as `Holds[key.type]` brands evidence with a singleton type. Salsa (behind rust-analyzer) stamps derived values with a revision and detects query cycles, as `Stamped` and `BlackHole` do.
- **Haskell.** The `ST` monad's rank-2 phantom `s` keeps an `STRef` from escaping its computation, as a singleton-typed `Holds` keeps evidence for one key from serving another. Kiselyov and Shan's *Lightweight Static Capabilities* and Noonan's *Ghosts of Departed Proofs* use phantom-typed names and proofs to carry facts the type checker enforces, which is the role of `Holds`, `Monitor` and `Published.Acquired`. `MVar` and STM's `TVar` are runtime alternatives.
- **Java.** Error Prone's `@GuardedBy` and `@LazyInit` (the source of `benignRace`), and the Checker Framework's Lock Checker. `VarHandle` access modes are what `Published`, `Once` and `Publish` compile to.
- **Scala.** Capture checking (`-language:experimental.captureChecking`) tracks capabilities in types too, at the level of references rather than one parameter per operation.

## Build

```
sbt test
sbt doc
```

JDK 17 or later.

## License

Apache 2.0. Derived from the Scala 3 compiler; see `NOTICE`.
