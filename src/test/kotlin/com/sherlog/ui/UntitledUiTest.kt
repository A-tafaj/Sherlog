package com.sherlog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** New Untitled files through the real [App]: Ctrl+N, the File menu, typing, and saving. */
@OptIn(ExperimentalTestApi::class)
class UntitledUiTest {

    private val calls = Collections.synchronizedList(mutableListOf<String>())

    private fun ComposeUiTest.launch(): Workspace {
        lateinit var workspace: Workspace
        setContent {
            val scope = rememberCoroutineScope()
            workspace = remember { Workspace(scope) }
            MaterialTheme {
                App(
                    workspace,
                    onOpenFiles = { calls += "open files" },
                    onOpenFolder = {},
                    onExportClick = {},
                    onCopyText = {},
                    onSaveAs = { then -> calls += "save as"; then(false) },
                )
            }
        }
        waitForIdle()
        return workspace
    }

    private fun ComposeUiTest.awaitUntitled(ws: Workspace) {
        waitUntil(timeoutMillis = 10_000) { ws.active.isUntitled && !ws.active.isLoading && ws.active.index != null }
        waitForIdle()
    }

    private fun ComposeUiTest.ctrl(key: Key) {
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }
        waitForIdle()
    }

    @Test
    fun `ctrl+n opens an untitled file with the caret ready to type`() = runComposeUiTest {
        val ws = launch()
        ctrl(Key.N)
        awaitUntitled(ws)
        assertTrue(onAllNodesWithText("Untitled").fetchSemanticsNodes().isNotEmpty()) // the tab, the top bar
        onNode(isFocused()).performTextInput("hello")
        waitForIdle()
        assertEquals("hello", ws.active.editedLines[0])
        onNodeWithText("●").assertExists()
    }

    @Test
    fun `a selection across lines keeps the status bar to one line`() = runComposeUiTest {
        // The screenshot of 2026-11-14: a selection over several lines of one
        // row was quoted in the status bar as is, a line each, until the log
        // was squeezed out.
        val ws = launch()
        ctrl(Key.N)
        awaitUntitled(ws)
        val text = "a\na\naa\na\na\na"
        onNode(isFocused()).performTextInput(text)
        onNode(isFocused()).performTextInputSelection(TextRange(0, text.length))
        waitUntil(timeoutMillis = 10_000) { ws.active.highlightCount != null && !ws.active.highlightCounting }
        waitForIdle()

        val counter = onNodeWithText("lines contain \"", substring = true).fetchSemanticsNode()
        val counterText = counter.config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue("a⏎a⏎aa" in counterText, counterText)
        val oneLine = onNodeWithText("Errors").fetchSemanticsNode().size.height
        assertTrue(counter.size.height <= oneLine + 1, "counter is ${counter.size.height}px, one line is ${oneLine}px")

        onNodeWithText("No tags in this file").assertExists() // not `No tags match ""`
    }

    @Test
    fun `the status bar quotes a needle on one line`() {
        assertEquals("a⏎b", counterNeedle("a\nb"))
        assertEquals("a⏎b", counterNeedle("a\r\nb"))
        assertEquals("x".repeat(24) + "…", counterNeedle("x".repeat(30)))
    }

    @Test
    fun `the file menu has new file too`() = runComposeUiTest {
        val ws = launch()
        onNodeWithText("File ▾").performClick()
        onNodeWithText("New File").performClick()
        awaitUntitled(ws)
        assertEquals("Untitled", ws.active.displayName)
    }

    @Test
    fun `saving an untitled file asks where, like save as`() = runComposeUiTest {
        val ws = launch()
        ctrl(Key.N)
        awaitUntitled(ws)
        onNode(isFocused()).performTextInput("notes")
        waitForIdle()
        ctrl(Key.S)
        assertEquals(listOf("save as"), calls)
        assertTrue(ws.active.isDirty) // the dialog was cancelled: nothing saved, nothing lost
    }
}
