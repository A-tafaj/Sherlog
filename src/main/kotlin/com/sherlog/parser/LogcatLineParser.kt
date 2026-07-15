package com.sherlog.parser

import com.sherlog.model.LogLevel

/**
 * Parses lines in logcat "threadtime" format, with or without a year:
 *
 * ```
 * 07-12 14:10:14.880   715   822 E AudioService: message text
 * 2026-07-14 14:27:36.530 22886 46555 I WifiService: message text
 * ```
 *
 * The year-prefixed variant is what some cached-log dumps use.
 *
 * Timestamps are stored as milliseconds since 2000-01-01 with real calendar
 * math. Lines WITHOUT a year are interpreted as year 2000 (a leap year, so
 * 02-29 always parses) — their values are therefore always smaller than one
 * year, which is also how [formatTimestamp] decides which style to print.
 *
 * Hand-rolled instead of regex because it runs once per line on files with
 * millions of lines. The caller supplies a reusable [Result] to avoid
 * allocating per line.
 */
object LogcatLineParser {

    class Result {
        /** Milliseconds since 2000-01-01 (no-year lines = year 2000). */
        var timestampMs: Long = 0
        var pid: Int = -1
        var tid: Int = -1
        var level: LogLevel = LogLevel.UNKNOWN
        var tag: String = ""
    }

    private const val MS_PER_DAY = 86_400_000L

    // Cumulative days before each month, leap and non-leap.
    private val daysBeforeMonthLeap = intArrayOf(0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335)
    private val daysBeforeMonthNonLeap = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

    private fun isLeap(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    private fun daysBeforeMonth(year: Int): IntArray =
        if (isLeap(year)) daysBeforeMonthLeap else daysBeforeMonthNonLeap

    /** Leap years in [1, y-1]. */
    private fun leapsBefore(y: Int): Int = (y - 1) / 4 - (y - 1) / 100 + (y - 1) / 400

    /** Days from 2000-01-01 to the start of [year]. */
    private fun daysSince2000(year: Int): Int =
        (year - 2000) * 365 + (leapsBefore(year) - leapsBefore(2000))

    /**
     * Parses [line] into [out]. Returns false (leaving [out] undefined) when
     * the line is not in a threadtime format.
     */
    fun parse(line: String, out: Result): Boolean {
        if (line.length < 25) return false

        // Optional "YYYY-" prefix (year-prefixed cached logs).
        var year = 2000
        var o = 0
        if (line[4] == '-' && digits4(line, 0) >= 2000) {
            year = digits4(line, 0)
            if (year > 2999) return false
            o = 5
            if (line.length < 30) return false
        }

        if (line[o + 2] != '-' || line[o + 5] != ' ' || line[o + 8] != ':' ||
            line[o + 11] != ':' || line[o + 14] != '.'
        ) return false

        val month = digits2(line, o)
        val day = digits2(line, o + 3)
        val hour = digits2(line, o + 6)
        val minute = digits2(line, o + 9)
        val second = digits2(line, o + 12)
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 60) return false
        val ms = digits3(line, o + 15)
        if (ms < 0) return false

        val days = daysSince2000(year) + daysBeforeMonth(year)[month - 1] + (day - 1)
        out.timestampMs = days * MS_PER_DAY + (((hour * 60L + minute) * 60 + second) * 1000) + ms

        var i = o + 18
        i = skipSpaces(line, i)
        val pidEnd = endOfDigits(line, i)
        if (pidEnd == i) return false
        out.pid = line.substring(i, pidEnd).toInt()

        i = skipSpaces(line, pidEnd)
        val tidEnd = endOfDigits(line, i)
        if (tidEnd == i) return false
        out.tid = line.substring(i, tidEnd).toInt()

        i = skipSpaces(line, tidEnd)
        if (i >= line.length) return false
        val level = LogLevel.fromLetter(line[i])
        if (level == LogLevel.UNKNOWN) return false
        if (i + 1 >= line.length || line[i + 1] != ' ') return false
        out.level = level

        i = skipSpaces(line, i + 1)
        // Tag runs until the first ':'. Some tags contain spaces, so do not
        // stop at whitespace; trim trailing padding instead.
        val colon = line.indexOf(':', i)
        if (colon <= i) return false
        out.tag = line.substring(i, colon).trimEnd()
        if (out.tag.isEmpty()) return false
        return true
    }

    /**
     * Parses "MM-DD HH:MM:SS[.mmm]" or "YYYY-MM-DD HH:MM:SS[.mmm]" as
     * entered in the time-range filter.
     */
    fun parseTimestamp(text: String): Long? {
        val t = text.trim()
        var year = 2000
        var o = 0
        if (t.length >= 19 && t[4] == '-' && digits4(t, 0) >= 2000) {
            year = digits4(t, 0)
            if (year > 2999) return null
            o = 5
        }
        if (t.length < o + 14) return null
        if (t[o + 2] != '-' || t[o + 5] != ' ' || t[o + 8] != ':' || t[o + 11] != ':') return null
        val month = digits2(t, o)
        val day = digits2(t, o + 3)
        val hour = digits2(t, o + 6)
        val minute = digits2(t, o + 9)
        val second = digits2(t, o + 12)
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 60) return null
        val ms = if (t.length >= o + 18 && t[o + 14] == '.') digits3(t, o + 15).coerceAtLeast(0) else 0
        val days = daysSince2000(year) + daysBeforeMonth(year)[month - 1] + (day - 1)
        return days * MS_PER_DAY + (((hour * 60L + minute) * 60 + second) * 1000) + ms
    }

    /**
     * Formats a timestamp produced by [parse]. Values within the reference
     * year (no-year source lines) print as "MM-DD HH:MM:SS.mmm"; anything
     * later prints as "YYYY-MM-DD HH:MM:SS.mmm".
     */
    fun formatTimestamp(timestampMs: Long): String {
        var rest = timestampMs
        val ms = (rest % 1000).toInt(); rest /= 1000
        val second = (rest % 60).toInt(); rest /= 60
        val minute = (rest % 60).toInt(); rest /= 60
        val hour = (rest % 24).toInt(); rest /= 24
        var days = rest.toInt()

        var year = 2000
        while (true) {
            val inYear = if (isLeap(year)) 366 else 365
            if (days < inYear) break
            days -= inYear
            year++
        }
        val table = daysBeforeMonth(year)
        var month = 12
        for (m in 1..12) {
            if (days < table.getOrElse(m) { 366 }) {
                month = m
                break
            }
        }
        days -= table[month - 1]

        return if (year == 2000) {
            "%02d-%02d %02d:%02d:%02d.%03d".format(month, days + 1, hour, minute, second, ms)
        } else {
            "%04d-%02d-%02d %02d:%02d:%02d.%03d".format(year, month, days + 1, hour, minute, second, ms)
        }
    }

    private fun digits2(s: String, at: Int): Int {
        val a = s[at]; val b = s[at + 1]
        if (a !in '0'..'9' || b !in '0'..'9') return -1
        return (a - '0') * 10 + (b - '0')
    }

    private fun digits3(s: String, at: Int): Int {
        if (at + 2 >= s.length) return -1
        val a = s[at]; val b = s[at + 1]; val c = s[at + 2]
        if (a !in '0'..'9' || b !in '0'..'9' || c !in '0'..'9') return -1
        return (a - '0') * 100 + (b - '0') * 10 + (c - '0')
    }

    private fun digits4(s: String, at: Int): Int {
        if (at + 3 >= s.length) return -1
        var value = 0
        for (i in at until at + 4) {
            val c = s[i]
            if (c !in '0'..'9') return -1
            value = value * 10 + (c - '0')
        }
        return value
    }

    private fun skipSpaces(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i] == ' ') i++
        return i
    }

    private fun endOfDigits(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i] in '0'..'9') i++
        return i
    }
}
