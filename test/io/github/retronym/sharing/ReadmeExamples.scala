package io.github.retronym.sharing

import scala.collection.mutable

import org.junit.Assert.*
import org.junit.Test

/** The README's highlights, compiled and run once each. */
class ReadmeExamples:
  import ReadmeExamples.*

  @Test def lazyFact(): Unit =
    given Mode = Mode.Concurrent
    given ClaimRegistry = Concurrency.Serialised.newRegistry(() => Claimant())
    val s = Symbol("x")
    assertSame(s.info, s.info)
    assertEquals(1, computed)

  @Test def monitor(): Unit =
    given Mode = Mode.Concurrent
    registry.add(Entry("a"))
    assertEquals(List("a"), registry.names)

  @Test def published(): Unit =
    val e = Entry("e")
    assertNull(e.read(1))
    e.write(1, "one")
    assertEquals("one", e.read(1))

  @Test def stamped(): Unit =
    val m = Library()
    assertEquals(List("a"), m.exportedNames(using Stamp(1)))
    assertEquals(List("a"), m.exportedNames(using Stamp(1)))
    assertEquals(1, m.computations)

  @Test def chosenOnce(): Unit =
    val concurrency = Concurrency.Parallel
    given Mode = concurrency.mode
    given ClaimRegistry = concurrency.newRegistry(() => claimantOfThisThread())
    val s = Symbol("y")
    assertSame(s.info, s.info)

object ReadmeExamples:
  final class Info
  var computed = 0
  def computeInfo(): Info = { computed += 1; Info() }

  class Symbol(name: String)(using Mode, ClaimRegistry):
    private var myInfo: Info | Null = null
    // the writer's state: reachable only with the claim's evidence
    private def store(i: Info)(using Sharing.Holds[this.type]): Unit = myInfo = i

    def info: Info =
      Sharing.once(this)(myInfo):   // peek; on a miss, claim `this` and peek again
        val i = computeInfo()
        store(i)
        i

  object Symbol:
    given Sharing.ClaimKind[Symbol] = Sharing.ClaimKind(What("computing info", null), lockWhenSerialised = true)

  object registry extends Sharing.Guard:
    private val byName = guarded(mutable.HashMap.empty[String, Entry])
    def add(e: Entry)(using Mode): Unit = locked(byName()(e.name) = e)
    def names(using Mode): List[String] = locked(byName().keys.toList)

  class Entry(val name: String):
    private[sharing] var version: Published[Int] = Published(0)
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

  sealed trait PerGeneration

  class Library:
    var computations = 0
    def computeExports(): List[String] = { computations += 1; List("a") }
    private var exports: Stamped[PerGeneration, List[String]] = Stamped.Empty
    def exportedNames(using Stamp[PerGeneration]): List[String] =
      exports.cached(computeExports())

  private val claimants = ThreadLocal.withInitial(() => Claimant())
  def claimantOfThisThread(): Claimant = claimants.get.nn
