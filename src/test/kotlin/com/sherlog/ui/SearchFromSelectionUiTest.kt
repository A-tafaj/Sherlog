package com.sherlog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
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
 * Ctrl+F searches for what is selected in a line, and Enter steps through the
 * matches from where the user is. TOKEN sits on every tenth line (5, 15, …).
 */
@OptIn(ExperimentalTestApi::class)
class SearchFromSelectionUiTest {

    private val count = 200
    private val lines = (0 until count).map { i ->
        val msg = if (i % 10 == 5) "TOKEN here" else "plain text"
        "07-12 10:%02d:%02d.000  1000  1000 I FindTest: %s %03d".format(i / 60, i % 60, msg, i)
    }
    private val logFile = File.createTempFile("searchsel", ".txt").apply { writeText(lines.joinToString("\n", postfix = "\n")) }

    @AfterTest
    fun cleanup() {
        logFile.delete()
    }

    private fun ComposeUiTest.launchApp(): AppState {
        lateinit var state: AppState
        setContent {
            val scope = rememberCoroutineScope()
            val workspace = remember { Workspace(scope) }
            state = workspace.active
            MaterialTheme { App(workspace, onOpenFiles = {}, onOpenFolder = {}, onExportClick = {}, onCopyText = {}) }
        }
        runOnIdle { state.openFile(logFile) }
        waitUntil(timeoutMillis = 10_000) { state.filteredLines.size == count && !state.isLoading }
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.rowBounds(n: Int) =
        onNode(hasText("%03d".format(n), substring = true)).fetchSemanticsNode().boundsInRoot

    /** Drags inside one line, which selects text there and drives the highlight. */
    private fun ComposeUiTest.selectInsideRow(n: Int) {
        val row = rowBounds(n)
        onRoot().performMouseInput {
            moveTo(Offset(row.left + 12f, row.center.y))
            press(MouseButton.Primary)
            moveTo(Offset(row.left + 120f, row.center.y))
            release()
        }
        waitForIdle()
    }

    private fun ComposeUiTest.press(key: Key, ctrl: Boolean = false, shift: Boolean = false) {
        onRoot().performKeyInput {
            when {
                ctrl -> withKeyDown(Key.CtrlLeft) { pressKey(key) }
                shift -> withKeyDown(Key.ShiftLeft) { pressKey(key) }
                else -> pressKey(key)
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.scrollTo(position: Int) {
        onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("FindTest:", substring = true)))
            .performScrollToIndex(position)
        waitForIdle()
    }

    private fun currentLine(state: AppState) =
        state.currentMatchPosition.let { if (it < 0) -1 else state.filteredLines[it] }

    @Test
    fun `ctrl+F puts the selected text in the search box`() = runComposeUiTest {
        val state = launchApp()
        selectInsideRow(3)
        val selected = state.selectionHighlight
        assertTrue(selected.isNotEmpty(), "the drag should have selected something")

        press(Key.F, ctrl = true)
        assertEquals(selected, state.searchText)
        assertEquals(SearchMode.FILTER, state.searchMode) // the mode is left alone
        // Filter mode narrows the view to the lines holding it.
        waitUntil(timeoutMillis = 10_000) { state.filteredLines.size < count }
        assertTrue(state.filteredLines.isNotEmpty())
    }

    @Test
    fun `ctrl+F with nothing selected just focuses the box`() = runComposeUiTest {
        val state = launchApp()
        press(Key.F, ctrl = true)
        assertEquals("", state.searchText)
        // Enter now goes to the search box, proving it took focus.
        press(Key.Enter)
        assertEquals(count, state.filteredLines.size)
    }

    @Test
    fun `enter steps to the nearest match and shift+enter goes back`() = runComposeUiTest {
        val state = launchApp()
        runOnIdle {
            state.searchMode = SearchMode.FIND
            state.onViewerSelection(50, "TOKEN") // as a double-click in that line would
        }
        waitUntil(timeoutMillis = 10_000) { !state.highlightCounting && state.highlightCount == 20 }
        scrollTo(60)

        press(Key.F, ctrl = true)
        assertEquals("TOKEN", state.searchText)
        waitUntil(timeoutMillis = 10_000) { !state.highlightCounting && state.highlightMatches.size == 20 }

        press(Key.Enter)
        assertEquals(65, currentLine(state)) // the first match on screen, not the first in the file
        press(Key.Enter)
        assertEquals(75, currentLine(state))
        press(Key.Enter, shift = true)
        assertEquals(65, currentLine(state))
    }

    @Test
    fun `enter outside the search box is left to whatever has focus`() = runComposeUiTest {
        val state = launchApp()
        runOnIdle {
            state.searchMode = SearchMode.FIND
            state.searchText = "TOKEN"
            state.onSearchChanged()
        }
        waitUntil(timeoutMillis = 10_000) { !state.highlightCounting && state.highlightMatches.size == 20 }

        // Focus a log row, then press Enter: no navigation.
        onRoot().performMouseInput { moveTo(rowBounds(3).center); press(MouseButton.Primary); release() }
        waitForIdle()
        press(Key.Enter)
        assertEquals(-1, state.currentMatchPosition)
    }
}
