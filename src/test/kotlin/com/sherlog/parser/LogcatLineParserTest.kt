package com.sherlog.parser

import com.sherlog.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogcatLineParserTest {

    private fun parse(line: String): LogcatLineParser.Result? {
        val out = LogcatLineParser.Result()
        return if (LogcatLineParser.parse(line, out)) out else null
    }

    @Test
    fun `parses standard threadtime line`() {
        val r = parse("""07-12 14:10:14.880   715   822 E AudioService: gain value not parsable : """"")
        assertNotNull(r)
        assertEquals(715, r.pid)
        assertEquals(822, r.tid)
        assertEquals(LogLevel.ERROR, r.level)
        assertEquals("AudioService", r.tag)
    }

    @Test
    fun `parses debug line with wide pid`() {
        val r = parse("07-12 14:10:16.620  6432 27697 D CCodec: int32_t height = 528")
        assertNotNull(r)
        assertEquals(6432, r.pid)
        assertEquals(27697, r.tid)
        assertEquals(LogLevel.DEBUG, r.level)
        assertEquals("CCodec", r.tag)
    }

    @Test
    fun `timestamp is ordered and round-trips`() {
        val a = parse("07-12 14:10:16.620  6432 27697 D CCodec: x")!!
        val b = parse("07-12 14:10:18.100  1913 18142 E OkHttp: timeout")!!
        assertTrue(b.timestampMs > a.timestampMs)
        assertEquals(1480, b.timestampMs - a.timestampMs)
        assertEquals("07-12 14:10:16.620", LogcatLineParser.formatTimestamp(a.timestampMs))
    }

    @Test
    fun `rejects non-log lines`() {
        assertNull(parse("--------- beginning of main"))
        assertNull(parse(""))
        assertNull(parse("    at com.example.Foo.bar(Foo.kt:42)"))
        assertNull(parse("random text that is long enough to pass the length check"))
    }

    @Test
    fun `rejects invalid month and level`() {
        assertNull(parse("13-12 14:10:16.620  6432 27697 D CCodec: x"))
        assertNull(parse("07-12 14:10:16.620  6432 27697 Q CCodec: x"))
    }

    @Test
    fun `parses tag containing spaces up to colon`() {
        val r = parse("07-12 14:10:16.620  6432 27697 I My Tag Name: hello")
        assertNotNull(r)
        assertEquals("My Tag Name", r.tag)
    }

    @Test
    fun `parseTimestamp accepts filter input formats`() {
        assertEquals(
            parse("07-12 14:00:00.000  1 1 I a: x")!!.timestampMs,
            LogcatLineParser.parseTimestamp("07-12 14:00:00"),
        )
        assertNotNull(LogcatLineParser.parseTimestamp("02-29 00:00:00")) // leap day must parse
        assertNull(LogcatLineParser.parseTimestamp("garbage"))
        assertNull(LogcatLineParser.parseTimestamp("14:00:00")) // date part required
    }

    @Test
    fun `leap year day boundaries stay ordered`() {
        val feb29 = LogcatLineParser.parseTimestamp("02-29 23:59:59")!!
        val mar01 = LogcatLineParser.parseTimestamp("03-01 00:00:00")!!
        assertTrue(mar01 > feb29)
        assertFalse(mar01 - feb29 > 1000)
    }
}
