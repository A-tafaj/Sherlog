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
    fun `parses year-prefixed format`() {
        val r = parse("2026-07-14 14:27:36.530 22886 46555 I WifiService: \t\"Interfaces\":\t[{")
        assertNotNull(r)
        assertEquals(22886, r.pid)
        assertEquals(46555, r.tid)
        assertEquals(LogLevel.INFO, r.level)
        assertEquals("WifiService", r.tag)
    }

    @Test
    fun `year timestamps round-trip and order across year boundaries`() {
        val a = parse("2025-12-31 23:59:59.999   100   100 I a: x")!!
        val b = parse("2026-01-01 00:00:00.000   100   100 I a: x")!!
        assertTrue(b.timestampMs > a.timestampMs)
        assertEquals(1, b.timestampMs - a.timestampMs)
        assertEquals("2026-07-14 14:27:36.530", LogcatLineParser.formatTimestamp(
            parse("2026-07-14 14:27:36.530 22886 46555 I Tag: x")!!.timestampMs,
        ))
    }

    @Test
    fun `year format uses real calendar math for non-leap february`() {
        val feb28 = parse("2026-02-28 23:59:59.999   100   100 I a: x")!!
        val mar01 = parse("2026-03-01 00:00:00.000   100   100 I a: x")!!
        assertEquals(1, mar01.timestampMs - feb28.timestampMs) // 2026 is not a leap year
        val leapFeb29 = parse("2028-02-29 00:00:00.000   100   100 I a: x")
        assertNotNull(leapFeb29) // 2028 is
    }

    @Test
    fun `parseTimestamp accepts year format and matches parsed lines`() {
        assertEquals(
            parse("2026-07-14 14:27:36.530 22886 46555 I Tag: x")!!.timestampMs,
            LogcatLineParser.parseTimestamp("2026-07-14 14:27:36.530"),
        )
        assertEquals(
            parse("2026-07-14 14:27:36.000 22886 46555 I Tag: x")!!.timestampMs,
            LogcatLineParser.parseTimestamp("2026-07-14 14:27:36"),
        )
    }

    @Test
    fun `no-year and year formats do not collide`() {
        // No-year lines live in the reference year and always sort before
        // year-2001+ lines; format style is chosen by that boundary.
        val noYear = parse("07-12 14:10:16.620  6432 27697 D CCodec: x")!!
        val withYear = parse("2026-07-12 14:10:16.620  6432 27697 D CCodec: x")!!
        assertTrue(withYear.timestampMs > noYear.timestampMs)
        assertEquals("07-12 14:10:16.620", LogcatLineParser.formatTimestamp(noYear.timestampMs))
    }

    @Test
    fun `parses microsecond timestamps from logcat -v usec`() {
        val r = parse("09-21 08:29:41.227589   483   483 I wlan: scan done")
        assertNotNull(r)
        assertEquals(483, r.pid)
        assertEquals(483, r.tid)
        assertEquals(LogLevel.INFO, r.level)
        assertEquals("wlan", r.tag)
        // Truncated to the millisecond, same value as the 3-digit form.
        assertEquals(parse("09-21 08:29:41.227   483   483 I wlan: scan done")!!.timestampMs, r.timestampMs)
    }

    @Test
    fun `parses nanosecond timestamps with and without a year`() {
        val noYear = parse("09-21 08:29:41.227589123   483   483 D wlan: x")
        assertNotNull(noYear)
        assertEquals("09-21 08:29:41.227", LogcatLineParser.formatTimestamp(noYear.timestampMs))
        val withYear = parse("2026-09-21 08:29:41.227589123   483   483 D wlan: x")
        assertNotNull(withYear)
        assertEquals("2026-09-21 08:29:41.227", LogcatLineParser.formatTimestamp(withYear.timestampMs))
    }

    @Test
    fun `rejects fraction digit counts logcat never prints`() {
        assertNull(parse("09-21 08:29:41.2275   483   483 I wlan: x"))
        assertNull(parse("09-21 08:29:41.22758   483   483 I wlan: x"))
        assertNull(parse("09-21 08:29:41.2275891   483   483 I wlan: x"))
    }

    @Test
    fun `parseTimestamp accepts microsecond input`() {
        assertEquals(
            LogcatLineParser.parseTimestamp("09-21 08:29:41.227"),
            LogcatLineParser.parseTimestamp("09-21 08:29:41.227589"),
        )
    }

    @Test
    fun `parses lines with an empty tag`() {
        // Kernel/driver lines (here the Broadcom dhd driver) print nothing before the colon.
        val r = parse("09-21 08:29:41.227589   483   483 W         : [dhd] CFG80211-ERROR) wl_cfg80211_scan : scan busy")
        assertNotNull(r)
        assertEquals("", r.tag)
        assertEquals(LogLevel.WARN, r.level)
        assertEquals(483, r.pid)
        val tight = parse("07-12 14:10:16.620  6432 27697 E : x")
        assertNotNull(tight)
        assertEquals("", tight.tag)
    }

    @Test
    fun `still rejects lines with no colon after the level`() {
        assertNull(parse("07-12 14:10:16.620  6432 27697 E no colon anywhere in this line"))
    }

    @Test
    fun `leap year day boundaries stay ordered`() {
        val feb29 = LogcatLineParser.parseTimestamp("02-29 23:59:59")!!
        val mar01 = LogcatLineParser.parseTimestamp("03-01 00:00:00")!!
        assertTrue(mar01 > feb29)
        assertFalse(mar01 - feb29 > 1000)
    }
}
