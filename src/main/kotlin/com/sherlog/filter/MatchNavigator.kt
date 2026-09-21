package com.sherlog.filter

/**
 * Picks where next/prev lands among the highlight matches ([HighlightCounter]
 * output: ascending positions in the filtered view), relative to where the
 * user is rather than to the start of the file.
 *
 * While the current match is still on screen, navigation steps from it. Once
 * the user has scrolled it out of view (or before any jump), the screen is the
 * anchor: next takes the first match at or below the top of the screen, prev
 * the last one at or above its bottom, so both walk through the visible
 * matches in order before leaving the screen. Both wrap at the ends.
 */
object MatchNavigator {

    /** Index into [matches] to move to on "next"; -1 when there are none. */
    fun next(matches: IntArray, current: Int, visible: IntRange): Int {
        if (matches.isEmpty()) return -1
        if (current in matches.indices && matches[current] in visible) {
            return if (current + 1 < matches.size) current + 1 else 0
        }
        val i = firstAtOrAfter(matches, visible.first)
        return if (i < matches.size) i else 0
    }

    /** Index into [matches] to move to on "previous"; -1 when there are none. */
    fun prev(matches: IntArray, current: Int, visible: IntRange): Int {
        if (matches.isEmpty()) return -1
        if (current in matches.indices && matches[current] in visible) {
            return if (current > 0) current - 1 else matches.size - 1
        }
        val i = firstAtOrAfter(matches, visible.last + 1) - 1
        return if (i >= 0) i else matches.size - 1
    }

    /**
     * Index into [matches] of the match on file line [line], or -1 when that
     * line is filtered out or doesn't match. Lets the current match follow its
     * line across a recount, whose positions shift whenever the filter changes.
     */
    fun indexOfLine(filteredLines: IntArray, matches: IntArray, line: Int): Int {
        if (line < 0) return -1
        val pos = filteredLines.binarySearch(line)
        if (pos < 0) return -1
        return matches.binarySearch(pos).let { if (it >= 0) it else -1 }
    }

    private fun firstAtOrAfter(matches: IntArray, position: Int): Int {
        val i = matches.binarySearch(position)
        return if (i >= 0) i else -(i + 1)
    }
}
