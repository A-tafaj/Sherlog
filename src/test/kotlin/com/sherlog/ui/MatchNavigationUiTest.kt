package com.sherlog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the real [App] headlessly: next/prev must continue from where the
 * user is, never restart at the first match. The log has a NEEDLE on every
 * tenth line (5, 15, …, 145 — 15 matches) and no filters, so a line's number
 * is also its row position.
 */
@OptIn(ExperimentalTestApi::class)
class MatchNavigationUiTest {

    private val lines = 150

    private val logFile = File.createTempFile("matchnav", ".txt").apply {
        writeText((0 until lines).joinToString("\n", postfix = "\n") { i ->
            val msg = if (i % 10 == 5) "NEEDLE at %03d".format(i) else "filler %03d".format(i)
            "07-12 14:%02d:%02d.000  1000  1000 I NavTest: %s".format(i / 60, i % 60, msg)
        })
    }

    @AfterTest
    fun cleanup() {
        logFile.delete()
    }

    @Test
    fun `next starts from the screen, not the first match, and steps through it without scrolling`() = runComposeUiTest {
        val state = launchApp()
        find(state, "NEEDLE")
        scrollViewerTo(60)

        pressF3()
        assertEquals(65, currentLine(state))
        row("filler 060").assertIsDisplayed() // the view stayed put

        pressF3()
        assertEquals(75, currentLine(state))
        row("filler 060").assertIsDisplayed()
    }

    @Test
    fun `previous from the screen takes the last match on it`() = runComposeUiTest {
        val state = launchApp()
        find(state, "NEEDLE")
        scrollViewerTo(60)

        pressShiftF3()
        val line = currentLine(state)
        assertTrue(line >= 65, "landed above the screen, on line $line")
        row("filler 060").assertIsDisplayed() // it was on screen, so the view stayed put
        row("NEEDLE at %03d".format(line)).assertIsDisplayed()
        assertTrue(!rowIsShown("NEEDLE at %03d".format(line + 10)), "a later match on screen was skipped")
    }

    @Test
    fun `scrolling away re-anchors on the new screen`() = runComposeUiTest {
        val state = launchApp()
        find(state, "NEEDLE")
        scrollViewerTo(60)
        pressF3()
        assertEquals(65, currentLine(state))

        scrollViewerTo(100)
        onNodeWithText("▼").performClick()
        assertEquals(105, currentLine(state))
    }

    @Test
    fun `stepping off the screen scrolls to the match`() = runComposeUiTest {
        val state = launchApp()
        find(state, "NEEDLE")
        scrollViewerTo(0)
        repeat(15) { pressF3() } // walk every match, scrolling as it goes
        assertEquals(145, currentLine(state))
        pressF3() // past the last one: wraps to the first
        assertEquals(5, currentLine(state))
        repeat(9) { pressF3() }
        assertEquals(95, currentLine(state))
        row("NEEDLE at 095").assertIsDisplayed()
    }

    @Test
    fun `clicking a found line in Find mode makes it the current match`() = runComposeUiTest {
        val state = launchApp()
        find(state, "NEEDLE")
        scrollViewerTo(60)

        row("NEEDLE at 075").performClick()
        waitForIdle()
        assertEquals(75, currentLine(state))

        pressF3()
        assertEquals(85, currentLine(state))
    }

    @Test
    fun `a selected occurrence becomes current and keeps its line across a filter change`() = runComposeUiTest {
        val state = launchApp()
        scrollViewerTo(60)
        runOnIdle { state.onViewerSelection(85, "NEEDLE") }
        waitForCount(state, 15)
        assertEquals(85, currentLine(state))

        // Drop every filler line: positions shift (line 85 moves to row 8).
        runOnIdle {
            state.editExcludeText("filler")
            state.scheduleApply(0)
        }
        waitUntil(timeoutMillis = 10_000) { state.filteredLines.size == 15 && !state.highlightCounting }
        assertEquals(85, currentLine(state))

        onNodeWithText("▼").performClick()
        assertEquals(95, currentLine(state))
    }

    @Test
    fun `clear filters drops a Find query's matches`() = runComposeUiTest {
        val state = launchApp()
        find(state, "NEEDLE")
        pressF3()
        assertEquals(5, currentLine(state))

        runOnIdle { state.clearFilters() }
        waitUntil(timeoutMillis = 10_000) { state.highlightCount == null && !state.highlightCounting }
        assertEquals(0, state.highlightMatches.size)
        pressF3()
        assertEquals(-1, state.currentMatchPosition)
    }

    // --- helpers ---

    private fun ComposeUiTest.launchApp(): AppState {
        lateinit var state: AppState
        setContent {
            val scope = rememberCoroutineScope()
            state = remember { AppState(scope) }
            MaterialTheme { App(state, onOpenClick = {}, onExportClick = {}) }
        }
        runOnIdle { state.openFile(logFile) }
        waitUntil(timeoutMillis = 10_000) { state.filteredLines.size == lines && !state.isBusy }
        return state
    }

    private fun ComposeUiTest.find(state: AppState, query: String) {
        runOnIdle {
            state.searchMode = SearchMode.FIND
            state.searchText = query
            state.onSearchChanged()
        }
        waitForCount(state, 15)
    }

    private fun ComposeUiTest.waitForCount(state: AppState, expected: Int) =
        waitUntil(timeoutMillis = 10_000) { !state.highlightCounting && state.highlightCount == expected }

    /** File line of the amber current match, or -1. */
    private fun currentLine(state: AppState): Int =
        state.currentMatchPosition.let { if (it < 0) -1 else state.filteredLines[it] }

    // The log viewer is the scrollable list holding log rows (the tag list
    // in the filter panel is the other one).
    private fun ComposeUiTest.scrollViewerTo(position: Int) {
        onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("NavTest:", substring = true)))
            .performScrollToIndex(position)
        waitForIdle()
    }

    private fun ComposeUiTest.row(text: String) = onNode(hasText(text, substring = true))

    private fun ComposeUiTest.rowIsShown(text: String): Boolean =
        runCatching { row(text).assertIsDisplayed() }.isSuccess

    private fun ComposeUiTest.pressF3() {
        onRoot().performKeyInput { pressKey(Key.F3) }
        waitForIdle()
    }

    private fun ComposeUiTest.pressShiftF3() {
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F3) } }
        waitForIdle()
    }
}
