package io.github.retronym.sharing

import scala.collection.mutable
import scala.compiletime.testing.typeCheckErrors

import org.junit.Assert.*
import org.junit.Test

/** `claimAt(owner)` and `Claimed[K, T]`: the code of a claim on a key computed from its owner is given
 *  `Holds[owner.type]`, and the owner's state in `Claimed` fields is not reachable without it, nor with another owner's.
 */
class ClaimAtTest:
  import ClaimAtTest.*

  @Test def evidenceReachesTheFamilysState(): Unit =
    for (m, r) <- List(Mode.Batch -> Concurrency.Serialised.newRegistry(() => Claimant()),
                       Mode.Concurrent -> Concurrency.Parallel.newRegistry(OnceTest.claimantOfThisThread)) do
      given Mode = m
      given ClaimRegistry = r
      val f = File("a")
      assertEquals(List(1, 2), List(f.read(), f.read()))
      assertEquals(List("a", "a"), f.lines)

  @Test def misuseDoesNotCompile(): Unit =
    // a file's state outside the claim: a read, a store, a collection
    val errs = typeCheckErrors("File(\"a\").reads()")
    assertTrue(errs.map(_.message).mkString, errs.exists(_.message.contains("must run as the one writer of")))
    assertFalse(typeCheckErrors("{ val f = File(\"a\"); f.reads = Claimed(1) }").isEmpty)
    assertFalse(typeCheckErrors("File(\"a\").seen() += \"x\"").isEmpty)
    // a plain value is not a `Claimed` one, nor the reverse
    assertFalse(typeCheckErrors("{ val f = File(\"a\"); f.reads = 1 }").isEmpty)
    assertFalse(typeCheckErrors("(File(\"a\").reads: Int)").isEmpty)
    // another file's claim: the evidence names its owner, not the kind of key
    assertFalse(typeCheckErrors(
      "{ given Mode = Mode.Batch; given ClaimRegistry = null; val f = File(\"a\"); val g = File(\"b\"); Sharing.claimAt(f)(f.key, kind, f)(g.reads()) }").isEmpty)
    // as it should be
    assertTrue(typeCheckErrors(
      "{ given Mode = Mode.Batch; given ClaimRegistry = null; val f = File(\"a\"); Sharing.claimAt(f)(f.key, kind, f) { f.reads = Claimed(f.reads() + 1) } }").isEmpty)

object ClaimAtTest:
  val kind: Sharing.ClaimKind[Any] = Sharing.ClaimKind(What("reading a file", null), lockWhenSerialised = true)

  final class File(name: String):
    // the key is computed per call, as a file's root is from its symbols
    def key: AnyRef = name.intern()
    var reads: Claimed[this.type, Int] = Claimed.initial(0)
    val seen: Claimed[this.type, mutable.ArrayBuffer[String]] = Claimed.initial(mutable.ArrayBuffer())

    def read()(using Mode, ClaimRegistry): Int = Sharing.claimAt(this)(key, kind, this):
      reads = Claimed(reads() + 1)
      seen() += name
      reads()
    def lines(using Mode, ClaimRegistry): List[String] = Sharing.claimAt(this)(key, kind, this)(seen().toList)
