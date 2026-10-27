package io.github.retronym.sharing

import java.util.concurrent.atomic.AtomicInteger

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.junit.Test

import SharingLincheckTest.{Key, LockPerKey, Lockable, OneLock, checkEveryWay}

/** `Sharing`'s operations at work in a toy compiler: the shape of dotc's symbol table, a graph that is made and
 *  evaluated lazily, read by several threads at once.
 *
 *  The toy and what it stands for:
 *
 *  - A `Sym` is a symbol with its denotation. It is made with a name and nothing else, as `newClassSymbol` makes a
 *    symbol whose denotation is still a completer's.
 *  - Its `members` are lazy structure: they do not exist until the symbol completes, and its completion makes them,
 *    as a class completer (the Namer, `ClassfileParser`, `TreeUnpickler`) enters the class's members into its scope.
 *    A symbol made twice for one definition, because two readers completed its owner, is the error this guards
 *    against; `created` counts the symbols made, which a second completion would change.
 *  - Its `info` is a lazy value: computed from its members' infos and from a symbol in another part of the graph (the
 *    `shared` leaf), as a class's type refers to its members' types and to a common base class (`Any`, `Object`) that
 *    every reader's work reaches. A `LazyRef`, a denotation's info or an inherited cache is a value of this kind.
 *  - Each lazy fact is a `Sharing.once` on a key of its own: the symbol for its members, as a completion root is,
 *    and a key per symbol for its info, as a `LazyRef` is its own key. A computation claims the facts it reads while
 *    it holds its own claim, so claims nest along the graph's edges, as completions nest: an info's claim holds over
 *    its members' completion and their infos' claims, and over the shared leaf's, which another thread may hold.
 *  - The registry is the base's: one coarse lock for serialised readers, a claim per key for parallel ones, and,
 *    in `SharingOnCompletionRootsLincheckTest`, dotc's completion roots themselves.
 *
 *  The specification is the toy itself in `Mode.Batch`: there every operation is the code it was before
 *  concurrency, so a batch toy is what the batch compiler computes. The graph is a tree of depth 2, two members a
 *  node, whose infos all reach the leaf `a.a`: in any order the infos and the number of symbols made are those of
 *  one thread evaluating the graph lazily.
 *
 *  Bounds as in `SharingLincheckTest`: per registry, model checking 20 scenarios of 500 interleavings, stress 10
 *  scenarios of 1000 runs, each of 3 threads of 3 calls.
 */
class ToyCompilerLincheckTest:
  import ToyCompilerLincheckTest.*

  @Test def lazyGraph(): Unit = checkEveryWay(classOf[BatchToy])(classOf[OneLockToy], classOf[LockPerKeyToy])

object ToyCompilerLincheckTest:

  /** The toy's symbol table: a root package whose members, and theirs, are made as they complete. */
  abstract class Toy(using Mode, ClaimRegistry):
    private val made = AtomicInteger()
    private val root = Sym("", depth = 0)
    // the common base class every info reaches: made by completing `a`, as `Any` is entered by completing `scala`
    private def shared: Sym = root.members.head.members.head

    @Operation def infoOfRoot(): Int = root.info
    @Operation def infoOfA(): Int = root.members.head.info
    @Operation def infoOfB(): Int = root.members(1).info
    @Operation def infoOfBB(): Int = root.members(1).members(1).info
    @Operation def created(): Int = made.get

    /** A symbol: its members and its info are lazy facts, each published whole (a final-field structure). */
    final class Sym(val name: String, depth: Int) extends Lockable:
      made.incrementAndGet()

      private var myMembers: Members | Null = null
      private var myInfo: Info | Null = null
      private val infoKey = InfoKey()

      /** The members, made by this symbol's completion: a class completer entering its scope. */
      def members: List[Sym] =
        val ms = Sharing.once(this)(myMembers):
          val made = Members(if depth == 2 then Nil else List("a", "b").map(n => Sym(name + n, depth + 1)))
          myMembers = made
          made
        ms.syms

      /** The info, computed from the members' infos and the shared base's: a lazy type. */
      def info: Int =
        val i = Sharing.once(infoKey)(myInfo):
          val base = if this eq shared then 0 else shared.info
          val computed = Info(name.length + 10 * members.map(_.info).sum + 100 * base)
          myInfo = computed
          computed
        i.n

    final class Members(val syms: List[Sym])
    given Sharing.ClaimKind[Sym] = Key.kind

  final class Info(val n: Int)
  final class InfoKey extends Lockable
  object InfoKey:
    given Sharing.ClaimKind[InfoKey] = Key.kind

  /** The specification: the toy evaluated by one thread, every operation as it was before concurrency. */
  class BatchToy extends Toy(using Mode.Batch, LockPerKey())

  class OneLockToy extends Toy(using Mode.Concurrent, OneLock())
  class LockPerKeyToy extends Toy(using Mode.Concurrent, LockPerKey())
