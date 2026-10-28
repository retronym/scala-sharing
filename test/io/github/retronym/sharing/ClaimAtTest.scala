package io.github.retronym.sharing

import scala.collection.mutable
import scala.compiletime.testing.typeCheckErrors

import org.junit.Assert.*
import org.junit.Test

/** `claimAt[K]` and `Claimed[K, T]`: the code of a claim on a family of keys is given `Holds[K]`, the family's state in
 *  `Claimed` fields is not reachable without it, and `K` must be given.
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
    // the family's state outside the claim: a read, a store, a collection
    val errs = typeCheckErrors("File(\"a\").reads()")
    assertTrue(errs.map(_.message).mkString, errs.exists(_.message.contains("must run as the one writer of")))
    assertFalse(typeCheckErrors("{ val f = File(\"a\"); f.reads = Claimed(1) }").isEmpty)
    assertFalse(typeCheckErrors("File(\"a\").seen() += \"x\"").isEmpty)
    // a plain value is not a `Claimed` one, nor the reverse
    assertFalse(typeCheckErrors("{ val f = File(\"a\"); f.reads = 1 }").isEmpty)
    assertFalse(typeCheckErrors("(File(\"a\").reads: Int)").isEmpty)
    // another family's evidence
    assertFalse(typeCheckErrors(
      "{ given Mode = Mode.Batch; given ClaimRegistry = null; val f = File(\"a\"); Sharing.claimAt[OtherClaim](f.key, kind, f)(f.reads()) }").isEmpty)
    // the family left to be inferred
    val errs2 = typeCheckErrors(
      "{ given Mode = Mode.Batch; given ClaimRegistry = null; val f = File(\"a\"); Sharing.claimAt(f.key, kind, f)(1) }")
    assertTrue(errs2.map(_.message).mkString, errs2.exists(_.message.contains("needs its family of keys given explicitly")))
    // as it should be
    assertTrue(typeCheckErrors(
      "{ given Mode = Mode.Batch; given ClaimRegistry = null; val f = File(\"a\"); Sharing.claimAt[FileClaim](f.key, kind, f) { f.reads = Claimed(f.reads() + 1) } }").isEmpty)

object ClaimAtTest:
  /** A family of keys: some file's claim. */
  sealed trait FileClaim
  sealed trait OtherClaim

  val kind: Sharing.ClaimKind[Any] = Sharing.ClaimKind(What("reading a file", null), lockWhenSerialised = true)

  final class File(name: String):
    // the key is computed per call, as a file's root is from its symbols
    def key: AnyRef = name.intern()
    var reads: Claimed[FileClaim, Int] = Claimed.initial(0)
    val seen: Claimed[FileClaim, mutable.ArrayBuffer[String]] = Claimed.initial(mutable.ArrayBuffer())

    def read()(using Mode, ClaimRegistry): Int = Sharing.claimAt[FileClaim](key, kind, this):
      reads = Claimed(reads() + 1)
      seen() += name
      reads()
    def lines(using Mode, ClaimRegistry): List[String] = Sharing.claimAt[FileClaim](key, kind, this)(seen().toList)
