package com.sherlog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.sherlog.model.LogLevel
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Folding the filter panel away and resizing it, driven through the real [App]. */
@OptIn(ExperimentalTestApi::class)
class FilterPanelCollapseUiTest {

    private val lines = (0 until 50).map { "07-12 10:00:%02d.000  1000  1000 I PanelTest: line %03d".format(it, it) }
    private val file = File.createTempFile("filterpanelui", ".txt").apply { writeText(lines.joinToString("\n", postfix = "\n")) }

    @AfterTest
    fun cleanup() {
        file.delete()
    }

    private fun ComposeUiTest.launch(): Workspace {
        lateinit var workspace: Workspace
        setContent {
            val scope = rememberCoroutineScope()
            workspace = remember { Workspace(scope) }
            MaterialTheme { App(workspace, onOpenFiles = {}, onOpenFolder = {}, onExportClick = {}, onCopyText = {}) }
        }
        runOnIdle { workspace.open(listOf(file)) }
        waitUntil(timeoutMillis = 10_000) { workspace.active.filteredLines.size == lines.size && !workspace.active.isLoading }
        waitForIdle()
        return workspace
    }

    private fun ComposeUiTest.ctrlB() {
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        waitForIdle()
    }

    private fun ComposeUiTest.panelShown(): Boolean =
        onAllNodes(hasText("Search tags")).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.dragEdge(byX: Float) {
        val edge = onNodeWithTag("filterPanelEdge").fetchSemanticsNode().boundsInRoot.center
        onRoot().performMouseInput {
            moveTo(edge)
            press(MouseButton.Primary)
            moveTo(Offset(edge.x + byX / 2, edge.y))
            moveTo(Offset(edge.x + byX, edge.y))
            release()
        }
        waitForIdle()
    }

    @Test
    fun `ctrl+b folds the panel down to a strip and back`() = runComposeUiTest {
        val ws = launch()
        assertTrue(panelShown())
        ctrlB()
        assertTrue(ws.prefs.filterPanelCollapsed)
        assertFalse(panelShown())
        onNodeWithTag("filterStrip").assertExists()
        ctrlB()
        assertTrue(panelShown())
    }

    @Test
    fun `the panel's own button folds it, and clicking the strip brings it back`() = runComposeUiTest {
        val ws = launch()
        onNodeWithText("«").performClick()
        waitForIdle()
        assertTrue(ws.prefs.filterPanelCollapsed)
        onNodeWithTag("filterStrip").performClick()
        waitForIdle()
        assertFalse(ws.prefs.filterPanelCollapsed)
        assertTrue(panelShown())
    }

    @Test
    fun `the strip keeps count of the filters still active`() = runComposeUiTest {
        val ws = launch()
        ctrlB()
        onNodeWithTag("filterStripCount").assertDoesNotExist()
        runOnIdle {
            ws.active.enabledLevels = ws.active.enabledLevels - LogLevel.VERBOSE
            ws.active.scheduleApply(0)
        }
        waitUntil(timeoutMillis = 5_000) { ws.active.activeFilterCount == 1 }
        waitForIdle()
        onNode(hasTestTag("filterStripCount") and hasAnyDescendant(hasText("1")), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `shortcuts still work after folding away a field that had focus`() = runComposeUiTest {
        val ws = launch()
        onNodeWithText("Search tags").performClick() // focus inside the panel
        waitForIdle()
        ctrlB()
        assertTrue(ws.prefs.filterPanelCollapsed)
        ctrlB() // would go nowhere if focus had been lost with the field
        assertFalse(ws.prefs.filterPanelCollapsed)
    }

    @Test
    fun `dragging the edge resizes the panel, far left folds it, and a double-click resets it`() = runComposeUiTest {
        val ws = launch()
        val start = ws.prefs.filterPanelWidth
        dragEdge(80f)
        assertTrue(ws.prefs.filterPanelWidth > start + 40, "width ${ws.prefs.filterPanelWidth}, was $start")
        assertFalse(ws.prefs.filterPanelCollapsed)

        dragEdge(-400f)
        assertTrue(ws.prefs.filterPanelCollapsed)

        onNodeWithTag("filterPanelEdge").performMouseInput { doubleClick() }
        waitForIdle()
        assertFalse(ws.prefs.filterPanelCollapsed)
        assertEquals(UiPrefs.DEFAULT_PANEL_WIDTH, ws.prefs.filterPanelWidth)
    }
}
