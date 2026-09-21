package com.sherlog.filter

import kotlin.test.Test
import kotlin.test.assertEquals

class MatchNavigatorTest {

    // Five 30-line screens with three matches each: positions 5, 15, 25 on
    // screen 1, 35, 45, 55 on screen 2, … — 15 matches in all.
    private val matches = IntArray(15) { 5 + it * 10 }
    private val screen1 = 0..29
    private val screen3 = 60..89 // holds match indices 6, 7, 8
    private val screen5 = 120..149

    @Test
    fun `next with no current match starts at the first match on screen, not the first in the file`() {
        assertEquals(6, MatchNavigator.next(matches, -1, screen3))
    }

    @Test
    fun `prev with no current match starts at the last match on screen`() {
        assertEquals(8, MatchNavigator.prev(matches, -1, screen3))
    }

    @Test
    fun `a current match on screen steps one at a time`() {
        assertEquals(8, MatchNavigator.next(matches, 7, screen3))
        assertEquals(6, MatchNavigator.prev(matches, 7, screen3))
    }

    @Test
    fun `stepping off the edge of the screen continues to the neighbouring match`() {
        assertEquals(9, MatchNavigator.next(matches, 8, screen3))
        assertEquals(5, MatchNavigator.prev(matches, 6, screen3))
    }

    @Test
    fun `once the current match is scrolled away the screen becomes the anchor`() {
        // Last landed on match 1 (screen 1), then scrolled down to screen 3.
        assertEquals(6, MatchNavigator.next(matches, 1, screen3))
        assertEquals(8, MatchNavigator.prev(matches, 1, screen3))
    }

    @Test
    fun `a screen without matches goes to the nearest one in the direction pressed`() {
        val gap = 86..94 // between match 8 (85) and match 9 (95)
        assertEquals(9, MatchNavigator.next(matches, -1, gap))
        assertEquals(8, MatchNavigator.prev(matches, -1, gap))
    }

    @Test
    fun `wraps around at both ends`() {
        assertEquals(0, MatchNavigator.next(matches, 14, screen5))
        assertEquals(14, MatchNavigator.prev(matches, 0, screen1))
        // Scrolled past the last match / above the first one.
        assertEquals(0, MatchNavigator.next(matches, -1, 146..149))
        assertEquals(14, MatchNavigator.prev(matches, -1, 0..4))
    }

    @Test
    fun `no matches means nowhere to go`() {
        assertEquals(-1, MatchNavigator.next(IntArray(0), -1, screen1))
        assertEquals(-1, MatchNavigator.prev(IntArray(0), -1, screen1))
    }

    @Test
    fun `indexOfLine follows a file line through the filtered view`() {
        val filteredLines = intArrayOf(2, 4, 7, 9, 12)
        val hits = intArrayOf(1, 3) // positions -> file lines 4 and 9
        assertEquals(0, MatchNavigator.indexOfLine(filteredLines, hits, 4))
        assertEquals(1, MatchNavigator.indexOfLine(filteredLines, hits, 9))
        assertEquals(-1, MatchNavigator.indexOfLine(filteredLines, hits, 7))  // in view, no match
        assertEquals(-1, MatchNavigator.indexOfLine(filteredLines, hits, 5))  // filtered out
        assertEquals(-1, MatchNavigator.indexOfLine(filteredLines, hits, -1)) // no current match
    }
}
