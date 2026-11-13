package com.sherlog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Log text zoom, driven through the real [App]. */
@OptIn(ExperimentalTestApi::class)
class ViewerZoomUiTest {

    private val lines = (0 until 200).map { "07-12 10:%02d:%02d.000  1000  1000 I ScaleTest: line %03d".format(it / 60, it % 60, it) }
    private val file = File.createTempFile("viewerzoomui", ".txt").apply { writeText(lines.joinToString("\n", postfix = "\n")) }

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

    private fun ComposeUiTest.ctrl(key: Key) {
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }
        waitForIdle()
    }

    private fun ComposeUiTest.rowHeight(): Float =
        onNode(hasText("line 002", substring = true)).fetchSemanticsNode().boundsInRoot.height

    @Test
    fun `ctrl+plus and ctrl+minus resize the log text, and ctrl+0 resets it`() = runComposeUiTest {
        val ws = launch()
        val normal = rowHeight()
        ctrl(Key.Equals)
        assertEquals(110, ws.prefs.zoomPercent)
        assertTrue(rowHeight() > normal, "rows should grow with the text")
        ctrl(Key.Minus)
        ctrl(Key.Minus)
        assertEquals(90, ws.prefs.zoomPercent)
        assertTrue(rowHeight() < normal)
        ctrl(Key.Zero)
        assertEquals(100, ws.prefs.zoomPercent)
        assertEquals(normal, rowHeight())
    }

    @Test
    fun `ctrl+wheel over the log zooms instead of scrolling`() = runComposeUiTest {
        val ws = launch()
        val center = onNode(hasText("line 002", substring = true)).fetchSemanticsNode().boundsInRoot.center
        onRoot().performMultiModalInput {
            key { keyDown(Key.CtrlLeft) }
            mouse { moveTo(center); scroll(-1f) } // wheel up
            key { keyUp(Key.CtrlLeft) }
        }
        waitForIdle()
        assertEquals(110, ws.prefs.zoomPercent)
        assertEquals(0, ws.active.listState.firstVisibleItemIndex)

        onRoot().performMultiModalInput { mouse { moveTo(center); scroll(3f) } } // plain wheel still scrolls
        waitForIdle()
        assertEquals(110, ws.prefs.zoomPercent)
        assertTrue(ws.active.listState.firstVisibleItemIndex > 0)
    }

    @Test
    fun `the status bar shows the zoom while it is not 100 percent, and a click resets it`() = runComposeUiTest {
        val ws = launch()
        onNodeWithText("Zoom", substring = true).assertDoesNotExist()
        ctrl(Key.Equals)
        ctrl(Key.Equals)
        onNodeWithText("Zoom 125%").performClick()
        waitForIdle()
        assertEquals(100, ws.prefs.zoomPercent)
        onNodeWithText("Zoom", substring = true).assertDoesNotExist()
    }
}
