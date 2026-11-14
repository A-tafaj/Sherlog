package com.sherlog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The match markers beside the scrollbar, driven through the real [App]. */
@OptIn(ExperimentalTestApi::class)
class MatchMarkersUiTest {

    private val total = 600
    // Every 50th line holds the needle: 12 matches, spread down the whole file.
    private val lines = (0 until total).map {
        "07-12 10:%02d:%02d.000  1000  1000 I MarkTest: line %03d%s".format(it / 60, it % 60, it, if (it % 50 == 25) " needle" else "")
    }
    private val file = File.createTempFile("matchmarkersui", ".txt").apply { writeText(lines.joinToString("\n", postfix = "\n")) }

    @AfterTest
    fun cleanup() {
        file.delete()
    }

    private fun ComposeUiTest.launch(): AppState {
        lateinit var state: AppState
        setContent {
            val scope = rememberCoroutineScope()
            val workspace = remember { Workspace(scope) }
            state = workspace.active
            MaterialTheme { App(workspace, onOpenFiles = {}, onOpenFolder = {}, onExportClick = {}, onCopyText = {}) }
        }
        runOnIdle { state.openFile(file) }
        waitUntil(timeoutMillis = 10_000) { state.filteredLines.size == total && !state.isBusy }
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.find(state: AppState, query: String) {
        runOnIdle {
            state.searchMode = SearchMode.FIND
            state.searchText = query
            state.onSearchChanged()
        }
        waitUntil(timeoutMillis = 10_000) { !state.highlightCounting && state.highlightCount == 12 }
        waitForIdle()
    }

    /** Clicks the marker lane where the line at [position] is drawn. */
    private fun ComposeUiTest.clickLaneAt(position: Int) {
        val lane = onNodeWithTag("matchMarkers")
        val height = lane.fetchSemanticsNode().size.height
        lane.performMouseInput { click(Offset(centerX, (position + 0.5f) / total * height)) }
        waitForIdle()
    }

    @Test
    fun `markers appear for find matches and a click jumps to that match`() = runComposeUiTest {
        val state = launch()
        onNodeWithTag("matchMarkers").assertDoesNotExist() // nothing to mark yet
        find(state, "needle")
        onNodeWithTag("matchMarkers").assertExists()

        clickLaneAt(475) // the 10th match, far below the first screen
        waitForIdle()
        assertEquals(9, state.currentMatchIndex)
        assertEquals(475, state.currentMatchPosition)
        val visible = state.listState.layoutInfo.visibleItemsInfo.map { it.index }
        assertTrue(475 in visible, "line 475 should be on screen, saw $visible")
    }

    @Test
    fun `a click between markers does nothing`() = runComposeUiTest {
        val state = launch()
        find(state, "needle")
        clickLaneAt(300) // halfway between matches at 275 and 325
        assertEquals(-1, state.currentMatchIndex)
        assertEquals(0, state.listState.firstVisibleItemIndex)
    }

    @Test
    fun `f3 carries on from a clicked marker`() = runComposeUiTest {
        val state = launch()
        find(state, "needle")
        clickLaneAt(275)
        assertEquals(5, state.currentMatchIndex)
        onRoot().performKeyInput { pressKey(Key.F3) }
        waitForIdle()
        assertEquals(325, state.currentMatchPosition)
    }

    @Test
    fun `filter mode marks nothing, since every line in view matches`() = runComposeUiTest {
        val state = launch()
        runOnIdle {
            state.searchText = "needle"
            state.onSearchChanged()
        }
        waitUntil(timeoutMillis = 10_000) { state.filteredLines.size == 12 && !state.isBusy }
        waitForIdle()
        onNodeWithTag("matchMarkers").assertDoesNotExist()
    }
}
