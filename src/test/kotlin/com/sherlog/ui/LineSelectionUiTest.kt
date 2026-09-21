package com.sherlog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.io.File
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives the real [App] headlessly with real mouse drags, clicks and keys. */
@OptIn(ExperimentalTestApi::class)
class LineSelectionUiTest {

    private val lines = (0 until 300).map { "07-12 10:%02d:%02d.000  1000  1000 I SelTest: line %03d".format(it / 60, it % 60, it) }
    private val file = File.createTempFile("lineselectionui", ".txt").apply { writeText(lines.joinToString("\n", postfix = "\n")) }
    private val copies = Collections.synchronizedList(mutableListOf<String>())

    @AfterTest
    fun cleanup() {
        file.delete()
    }

    private fun ComposeUiTest.launchApp(): AppState {
        lateinit var state: AppState
        setContent {
            val scope = rememberCoroutineScope()
            val workspace = remember { Workspace(scope) }
            state = workspace.active
            MaterialTheme {
                App(workspace, onOpenFiles = {}, onOpenFolder = {}, onExportClick = {}, onCopyText = { copies += it })
            }
        }
        runOnIdle { state.openFile(file) }
        waitUntil(timeoutMillis = 10_000) { state.filteredLines.size == lines.size && !state.isLoading }
        waitForIdle()
        return state
    }

    /** Middle of the row showing line [n], in root coordinates. */
    private fun ComposeUiTest.rowCenter(n: Int): Offset =
        onNode(hasText("line %03d".format(n), substring = true)).fetchSemanticsNode().boundsInRoot.center

    private fun expected(range: IntRange) = lines.slice(range).joinToString(System.lineSeparator())

    private fun ComposeUiTest.awaitCopy(): String {
        waitUntil(timeoutMillis = 10_000) { copies.isNotEmpty() }
        return copies.single()
    }

    private fun ComposeUiTest.drag(from: Offset, to: Offset) {
        onRoot().performMouseInput {
            moveTo(from)
            press(MouseButton.Primary)
            moveTo(Offset(from.x, (from.y + to.y) / 2))
            moveTo(to)
            release()
        }
        waitForIdle()
    }

    @Test
    fun `dragging across lines selects them, and ctrl+c copies them`() = runComposeUiTest {
        val state = launchApp()
        drag(rowCenter(3), rowCenter(7))
        assertEquals(3..7, state.lineSelection)

        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        assertEquals(expected(3..7), awaitCopy())
    }

    @Test
    fun `dragging upwards selects the same way`() = runComposeUiTest {
        val state = launchApp()
        drag(rowCenter(9), rowCenter(4))
        assertEquals(4..9, state.lineSelection)
    }

    @Test
    fun `a drag inside one line stays a text selection and highlights it`() = runComposeUiTest {
        val state = launchApp()
        val row = onNode(hasText("line 005", substring = true)).fetchSemanticsNode().boundsInRoot
        drag(Offset(row.left + 12f, row.center.y), Offset(row.left + 160f, row.center.y))
        assertNull(state.lineSelection)
        assertTrue(state.selectionHighlight.isNotEmpty(), "the in-line selection should drive the highlight")
    }

    @Test
    fun `click then shift-click selects the lines in between`() = runComposeUiTest {
        val state = launchApp()
        val first = rowCenter(10)
        val last = rowCenter(14)
        // click() without a position clicks the node's centre, not where the
        // mouse was moved to — hence the explicit positions.
        onRoot().performMultiModalInput {
            mouse { click(first) }
            key { keyDown(Key.ShiftLeft) }
            mouse { click(last) }
            key { keyUp(Key.ShiftLeft) }
        }
        waitForIdle()
        assertEquals(10..14, state.lineSelection)
    }

    @Test
    fun `dragging past the bottom edge scrolls and keeps selecting`() = runComposeUiTest {
        val state = launchApp()
        val viewer = onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("SelTest:", substring = true)))
            .fetchSemanticsNode().boundsInRoot
        val lastVisible = state.listState.layoutInfo.visibleItemsInfo.last().index
        onRoot().performMouseInput {
            moveTo(rowCenter(2))
            press(MouseButton.Primary)
            moveTo(Offset(viewer.center.x, viewer.bottom + 40f)) // below the list, over the status bar
        }
        mainClock.advanceTimeBy(1_000)
        onRoot().performMouseInput { release() }
        waitForIdle()
        val sel = state.lineSelection!!
        assertEquals(2, sel.first)
        assertTrue(sel.last > lastVisible + 5, "selection stopped at ${sel.last}; the screen ended at $lastVisible")
    }

    @Test
    fun `the status bar and the right-click menu copy the selection too`() = runComposeUiTest {
        val state = launchApp()
        drag(rowCenter(20), rowCenter(22))
        onNodeWithText("3 lines selected").assertExists()
        onNodeWithText("Copy").performClick()
        assertEquals(expected(20..22), awaitCopy())
        copies.clear()

        onRoot().performMouseInput { rightClick(rowCenter(21)) }
        waitForIdle()
        onNodeWithText("Copy 3 selected lines").performClick()
        assertEquals(expected(20..22), awaitCopy())

        onNodeWithText("✕").performClick()
        assertNull(state.lineSelection)
    }
}
