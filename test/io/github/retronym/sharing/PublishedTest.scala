package io.github.retronym.sharing

import scala.compiletime.testing.typeCheckErrors

import org.junit.Assert.*
import org.junit.Test

/** `Published` fields: each operation on an `Int`, a value class over an `Int` with a private field
 *  and a reference; a plain access does not type-check; and a reader that acquires the flag a writer released sees
 *  what the writer stored before it, on real threads: the message-passing (MP) litmus test.
 */
class PublishedTest:
  import PublishedTest.*

  @Test def roundTrips(): Unit =
    val c = Cell()
    assertEquals(0, c.flag.acquire)
    c.flag.release(3)
    assertEquals(3, c.flag.acquire)
    c.flag.setPlain(4)
    assertEquals(4, c.flag.plain)

    assertEquals(0, c.code.acquire.get)
    c.code.release(Code(7))
    assertEquals(7, c.code.acquire.get)
    c.code.setPlain(Code(8))
    assertEquals(8, c.code.plain.get)

    assertEquals("", c.name.acquire)
    c.name.release("x")
    assertEquals("x", c.name.acquire)

  @Test def plainAccessDoesNotTypeCheck(): Unit =
    assertFalse(typeCheckErrors("(Cell().flag: Int)").isEmpty)
    assertFalse(typeCheckErrors("{ val c = Cell(); c.flag = 1 }").isEmpty)
    assertFalse(typeCheckErrors("Cell().flag + 1").isEmpty)

  @Test def payloadRoundTrips(): Unit =
    val c = Cell()
    c.flag.publishing(5):
      c.payload := 42
    val p = c.flag.acquired
    assertEquals(5, p.value)
    assertEquals(42, c.payload.after(p))
    assertEquals(42, c.payload.plain)

  @Test def payloadMisuseDoesNotCompile(): Unit =
    // a plain read, a store outside a `publishing` block
    assertFalse(typeCheckErrors("(Cell().payload: Int)").isEmpty)
    assertFalse(typeCheckErrors("Cell().payload := 1").isEmpty)
    // a read after another object's acquire
    assertFalse(typeCheckErrors("{ val a = Cell(); val b = Cell(); b.payload.after(a.flag.acquired) }").isEmpty)
    // a store to another object's payload, and the evidence kept past the block
    assertFalse(typeCheckErrors("{ val a = Cell(); val b = Cell(); a.flag.publishing(1) { b.payload := 2 } }").isEmpty)
    assertFalse(typeCheckErrors("{ var kept: AnyRef | Null = null; val a = Cell(); a.flag.publishing(1) { kept = summon[Published.Publishing] } }").isEmpty)
    // and the well-formed version of each compiles
    assertTrue(typeCheckErrors("{ val a = Cell(); a.flag.publishing(1) { a.payload := 2 }; a.payload.after(a.flag.acquired) }").isEmpty)

  @Test def payloadMessagePassing(): Unit =
    // the writer stores the payload in a `publishing` block of the flag; a reader that acquires it sees the payload
    val rounds = 200_000
    var stale = 0
    for _ <- 1 to 4 do
      val cells = Array.fill(rounds)(Cell())
      val reader = Thread { () =>
        var i = 0
        while i < rounds do
          val c = cells(i)
          val p = c.flag.acquired
          if p.value == 1 then
            if c.payload.after(p) != 42 then stale += 1
            i += 1
          else Thread.onSpinWait()
      }
      reader.start()
      var i = 0
      while i < rounds do
        val c = cells(i)
        c.flag.publishing(1):
          c.payload := 42
        i += 1
      reader.join()
    assertEquals("payload reads after an acquire that found the flag", 0, stale)

  @Test def messagePassing(): Unit =
    // a writer stores the data, then releases the flag; a reader that acquires the flag sees the data
    val rounds = 200_000
    var stale = 0
    for _ <- 1 to 4 do
      val cells = Array.fill(rounds)(Cell())
      val reader = Thread { () =>
        var i = 0
        while i < rounds do
          val c = cells(i)
          if c.flag.acquire == 1 then
            if c.data != 42 then stale += 1
            i += 1
          else Thread.onSpinWait()
      }
      reader.start()
      var i = 0
      while i < rounds do
        val c = cells(i)
        c.data = 42
        c.flag.release(1)
        i += 1
      reader.join()
    assertEquals("reads of the data after an acquire that found the flag", 0, stale)

object PublishedTest:
  final class Code(private val raw: Int) extends AnyVal:
    def get: Int = raw

  final class Cell:
    var data: Int = 0
    var flag: Published[Int] = Published(0)
    var code: Published[Code] = Published(Code(0))
    var name: Published[String] = Published("")
    var payload: Payload[Cell, "flag", Int] = Payload(0)

  // each handle in a `val`, the given a reference to it: an alias given computed in place is a lazy val, checked
  // on every access
  val flagHandle: Published.Handle[Cell, "flag"] = Published.handle
  val codeHandle: Published.Handle[Cell, "code"] = Published.handle
  val nameHandle: Published.Handle[Cell, "name"] = Published.handle
  given Published.Handle[Cell, "flag"] = flagHandle
  given codeGiven: Published.Handle[Cell, "code"] = codeHandle
  given nameGiven: Published.Handle[Cell, "name"] = nameHandle

class PublishedPrivateFieldTest:
  @Test def privateFieldIsRejected(): Unit =
    val errs = scala.compiletime.testing.typeCheckErrors(
      "{ class P { private var f: Published[Int] = Published(0) }; Published.handle[P, \"f\"] }")
    assertTrue(errs.map(_.message).mkString, errs.exists(_.message.contains("is private")))
