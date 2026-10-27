# scala-sharing

Concurrency primitives for state shared between threads, for Scala 3. They come from the Scala 3 compiler's work on several readers over one symbol table ([retronym/scala3#84](https://github.com/retronym/scala3/pull/84)) and do not depend on the compiler. Package: `io.github.retronym.sharing`.

The state is shared only in `Mode.Concurrent`. In `Mode.Batch` each operation is the plain code it wraps: an `inline def` that compiles to a test of the mode and the original expression, with no allocation. Guarded state is reachable only through evidence parameters (`Holds`, `Monitor`), so touching it outside its claim or lock does not compile.

## Contents

- `Sharing`: `once` and `claim` for lazy facts computed by one writer while others wait; `Guard` / `Guarded`, `serialised` and `locking` for short critical sections; `BlackHole` for re-entry (cycle) detection.
- `Published` and `Payload`: a field read with acquire and written with release semantics, through a `VarHandle` declared beside its class, and the fields it publishes. Macros, so a plain access does not compile.
- `Publish`: release/acquire access to array slots.
- `Stamped`: a value and the stamp it was computed at, in one immutable object.
- `ClaimRegistry` and two implementations: `CoarseLock` (one lock, threads serialised) and `PerKeyClaims` (a claim per key, with a wait-for cycle check; `Claimant`, `CycleHandler`, `Cancelled`).
- `Concurrency`: `Batch`, `Serialised` or `Parallel`, with the mode and registry for the operations' givens.
- `Mode`, `What`, `Label`, `benignRace`.

The tests are Lincheck model-checking and stress suites, including a toy compiler symbol table, and compile-time checks of `Published`'s misuse.

## Build

```
sbt test
```

JDK 17 or later. Some doc comments still refer to the compiler and to `docs/_docs/internals/interactive-multi-reader.md` in the compiler's repository.

## License

Apache 2.0. Derived from the Scala 3 compiler; see `NOTICE`.
