package com.sherlog.parser

import com.sherlog.model.LogLevel

/**
 * Parses lines in logcat "threadtime" format:
 *
 * ```
 * 07-12 14:10:14.880   715   822 E AudioService: message text
 * ```
 *
 * Hand-rolled instead of regex because it runs once per line on files with
 * millions of lines. The caller supplies a reusable [Result] to avoid
 * allocating per line.
 */
object LogcatLineParser {

    class Result {
        /** Milliseconds since Jan 1 of a fixed reference year (year 2000, a leap year). */
        var timestampMs: Long = 0
        var pid: Int = -1
        var tid: Int = -1
        var level: LogLevel = LogLevel.UNKNOWN
        var tag: String = ""
    }

    // Cumulative days before each month in a leap year (logcat timestamps
    // carry no year, so 02-29 must always be parseable).
    private val daysBeforeMonth = intArrayOf(0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335)

    /**
     * Parses [line] into [out]. Returns false (leaving [out] undefined) when
     * the line is not in threadtime format.
     */
    fun parse(line: String, out: Result): Boolean {
        // "MM-DD HH:MM:SS.mmm" is 18 chars; the shortest valid line needs
        // at least pid, tid, level and a tag after it.
        if (line.length < 25) return false
        if (line[2] != '-' || line[5] != ' ' || line[8] != ':' || line[11] != ':' || line[14] != '.') return false

        val month = digits2(line, 0)
        val day = digits2(line, 3)
        val hour = digits2(line, 6)
        val minute = digits2(line, 9)
        val second = digits2(line, 12)
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 60) return false
        val ms = digits3(line, 15)
        if (ms < 0) return false

        val dayOfYear = daysBeforeMonth[month - 1] + (day - 1)
        out.timestampMs = ((((dayOfYear * 24L + hour) * 60 + minute) * 60 + second) * 1000) + ms

        var i = 18
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

    /** Parses "MM-DD HH:MM:SS" or "MM-DD HH:MM:SS.mmm" as entered in the time-range filter. */
    fun parseTimestamp(text: String): Long? {
        val t = text.trim()
        if (t.length < 14) return null
        if (t[2] != '-' || t[5] != ' ' || t[8] != ':' || t[11] != ':') return null
        val month = digits2(t, 0)
        val day = digits2(t, 3)
        val hour = digits2(t, 6)
        val minute = digits2(t, 9)
        val second = digits2(t, 12)
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 60) return null
        val ms = if (t.length >= 18 && t[14] == '.') digits3(t, 15).coerceAtLeast(0) else 0
        val dayOfYear = daysBeforeMonth[month - 1] + (day - 1)
        return ((((dayOfYear * 24L + hour) * 60 + minute) * 60 + second) * 1000) + ms
    }

    /** Formats a timestamp produced by [parse] back to "MM-DD HH:MM:SS.mmm". */
    fun formatTimestamp(timestampMs: Long): String {
        var rest = timestampMs
        val ms = (rest % 1000).toInt(); rest /= 1000
        val second = (rest % 60).toInt(); rest /= 60
        val minute = (rest % 60).toInt(); rest /= 60
        val hour = (rest % 24).toInt(); rest /= 24
        var dayOfYear = rest.toInt()
        var month = 12
        for (m in 1..12) {
            if (dayOfYear < daysBeforeMonth.getOrElse(m) { 366 }) { month = m; break }
        }
        dayOfYear -= daysBeforeMonth[month - 1]
        return "%02d-%02d %02d:%02d:%02d.%03d".format(month, dayOfYear + 1, hour, minute, second, ms)
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
