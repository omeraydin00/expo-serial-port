package expo.modules.serialport

import org.junit.Assert.assertEquals
import org.junit.Test

class FramerTest {
  private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
  private fun str(s: String) = s.toByteArray()

  /** Feeds chunks (bytes, time) and returns the emitted packets as strings. */
  private fun run(framer: Framer, vararg chunks: Pair<ByteArray, Long>, endAt: Long? = null): List<String> {
    val out = mutableListOf<String>()
    val emit: (ByteArray) -> Unit = { out.add(String(it)) }
    for ((data, t) in chunks) {
      framer.push(data, data.size, t, emit)
      framer.tick(t, emit)
    }
    if (endAt != null) framer.tick(endAt, emit)
    return out
  }

  @Test fun rawEmitsEveryChunk() {
    assertEquals(listOf("ab", "c"), run(Framer.Raw(), str("ab") to 0, str("c") to 1))
  }

  @Test fun rawSkipsEmptyReads() {
    assertEquals(emptyList<String>(), run(Framer.Raw(), ByteArray(0) to 0))
  }

  @Test fun rawUsesOnlyTheReadLength() {
    val out = mutableListOf<ByteArray>()
    Framer.Raw().push(bytes(1, 2, 3, 4), 2, 0) { out.add(it) }
    assertEquals(listOf(listOf<Byte>(1, 2)), out.map { it.toList() })
  }

  @Test fun delimiterJoinsSplitPackets() {
    val f = Framer.Delimiter(str("\r\n"))
    assertEquals(listOf("hello"), run(f, str("hel") to 0, str("lo\r\n") to 1))
  }

  @Test fun delimiterSplitAcrossReads() {
    val f = Framer.Delimiter(str("\r\n"))
    assertEquals(listOf("ok", "next"), run(f, str("ok\r") to 0, str("\nnext\r") to 1, str("\n") to 2))
  }

  @Test fun delimiterTwoPacketsInOneRead() {
    val f = Framer.Delimiter(bytes(0x03))
    val out = run(f, bytes('a'.code, 0x03, 'b'.code, 0x03, 'c'.code) to 0)
    assertEquals(listOf("a", "b"), out)
  }

  @Test fun delimiterEmptyPacket() {
    assertEquals(listOf("", "x"), run(Framer.Delimiter(str("\n")), str("\nx\n") to 0))
  }

  @Test fun delimiterCapsBufferWithoutDelimiter() {
    val out = mutableListOf<ByteArray>()
    val f = Framer.Delimiter(str("\n"))
    val chunk = ByteArray(Framer.MAX_PACKET + 10) { 'a'.code.toByte() }
    f.push(chunk, chunk.size, 0) { out.add(it) }
    assertEquals(listOf(Framer.MAX_PACKET), out.map { it.size })
  }

  @Test fun lengthCutsFixedPackets() {
    val f = Framer.Length(4)
    assertEquals(listOf("abcd", "efgh"), run(f, str("ab") to 0, str("cdefg") to 1, str("hi") to 2))
  }

  @Test fun idleWaitsForTheGap() {
    val f = Framer.Idle(20)
    assertEquals(emptyList<String>(), run(f, str("ab") to 0, str("cd") to 10, endAt = 25))
    assertEquals(listOf("abcd"), run(f, endAt = 30))
  }

  @Test fun idleSeparatesBursts() {
    val f = Framer.Idle(20)
    assertEquals(listOf("one", "two"), run(f, str("one") to 0, str("two") to 50, endAt = 100))
  }

  @Test fun idleNothingWithoutData() {
    assertEquals(emptyList<String>(), run(Framer.Idle(20), endAt = 1000))
  }

  @Test(expected = IllegalArgumentException::class)
  fun lengthRejectsZero() {
    Framer.Length(0)
  }
}
