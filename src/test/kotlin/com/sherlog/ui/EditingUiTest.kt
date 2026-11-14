package com.sherlog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Editing lines through the real [App]: typing, deleting, saving, and closing with unsaved changes. */
@OptIn(ExperimentalTestApi::class)
class EditingUiTest {

    private val dir: File = Files.createTempDirectory("editingui").toFile()
    private val lines = (0 until 40).map { "07-12 10:00:%02d.000  1000  1000 I EditTest: line %03d".format(it, it) }
    private val file = File(dir, "edit.txt").apply { writeText(lines.joinToString("\n", postfix = "\n")) }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private class Launched(val workspace: Workspace) {
        var exitRequested by mutableStateOf(false)
        var exited = false
        val tab get() = workspace.active
    }

    private fun ComposeUiTest.launch(): Launched {
        lateinit var launched: Launched
        setContent {
            val scope = rememberCoroutineScope()
            val app = remember { Launched(Workspace(scope)) }
            launched = app
            MaterialTheme {
                App(
                    app.workspace,
                    onOpenFiles = {}, onOpenFolder = {}, onExportClick = {}, onCopyText = {},
                    exitRequested = app.exitRequested,
                    onExit = { app.exited = true },
                    onExitCancelled = { app.exitRequested = false },
                )
            }
        }
        runOnIdle { launched.workspace.open(listOf(file)) }
        waitUntil(timeoutMillis = 10_000) { launched.tab.filteredLines.size == lines.size && !launched.tab.isLoading }
        waitForIdle()
        return launched
    }

    private fun ComposeUiTest.row(n: Int) = onNode(hasText("line %03d".format(n), substring = true))

    /** Clicks into line [n] and types [text] at the caret. */
    private fun ComposeUiTest.typeInto(n: Int, text: String) {
        row(n).performClick()
        row(n).performTextInput(text)
        waitForIdle()
    }

    private fun ComposeUiTest.ctrl(key: Key, shift: Boolean = false) {
        onRoot().performKeyInput {
            withKeyDown(Key.CtrlLeft) { if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key) }
        }
        waitForIdle()
    }

    @Test
    fun `typing into a line edits it, marks the tab, and enter adds nothing`() = runComposeUiTest {
        val app = launch()
        typeInto(5, "XYZ")
        val edited = app.tab.editedLines[5]!!
        assertTrue("XYZ" in edited, edited)
        assertEquals(lines[5].length + 3, edited.length)
        onNodeWithText("●").assertExists() // the tab's unsaved mark

        row(5).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(edited, app.tab.editedLines[5])
        assertContentEquals(lines.indices.toList().toIntArray(), app.tab.filteredLines)
    }

    @Test
    fun `delete removes the selected lines, and ctrl+shift+k the line with the caret`() = runComposeUiTest {
        val app = launch()
        val from = row(3).fetchSemanticsNode().boundsInRoot.center
        val to = row(5).fetchSemanticsNode().boundsInRoot.center
        onRoot().performMouseInput {
            moveTo(from); press(MouseButton.Primary); moveTo(Offset(from.x, (from.y + to.y) / 2)); moveTo(to); release()
        }
        waitForIdle()
        assertEquals(3..5, app.tab.lineSelection)
        row(5).performKeyInput { pressKey(Key.Delete) }
        waitForIdle()
        assertEquals(lines.size - 3, app.tab.filteredLines.size)
        assertFalse(3 in app.tab.filteredLines)

        row(10).performClick()
        waitForIdle()
        ctrl(Key.K, shift = true)
        assertFalse(10 in app.tab.filteredLines)
        assertEquals(4, app.tab.deletedCount)
    }

    @Test
    fun `ctrl+s saves edits straight away when no lines are hidden`() = runComposeUiTest {
        val app = launch()
        typeInto(2, "!")
        ctrl(Key.S)
        waitUntil(timeoutMillis = 10_000) { !app.tab.isDirty && !app.tab.isLoading && app.tab.index != null }
        val saved = file.readLines()
        assertEquals(lines.size, saved.size)
        assertTrue("!" in saved[2], saved[2])
        onNodeWithText("Only the", substring = true).assertDoesNotExist() // no hidden-lines question
    }

    @Test
    fun `closing a tab with unsaved changes asks first`() = runComposeUiTest {
        val app = launch()
        typeInto(1, "?")
        ctrl(Key.W)
        onNodeWithText("Save changes to edit.txt?").assertExists()
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertEquals("edit.txt", app.tab.file?.name) // still open, still edited
        assertTrue(app.tab.isDirty)

        ctrl(Key.W)
        onNodeWithText("Don't Save").performClick()
        waitForIdle()
        assertEquals(null, app.tab.file) // closed: the blank tab is left
        assertEquals(lines, file.readLines())
    }

    @Test
    fun `save in the close question writes the file, then closes the tab`() = runComposeUiTest {
        val app = launch()
        typeInto(1, "?")
        ctrl(Key.W)
        onNodeWithText("Save").performClick() // exact text: not the title
        waitUntil(timeoutMillis = 10_000) { app.tab.file == null }
        assertTrue("?" in file.readLines()[1])
    }

    @Test
    fun `quitting with unsaved changes asks, and don't save quits`() = runComposeUiTest {
        val app = launch()
        typeInto(1, "?")
        runOnIdle { app.exitRequested = true }
        waitForIdle()
        onNodeWithText("Save changes to edit.txt?").assertExists()
        assertFalse(app.exited)
        onNodeWithText("Don't Save").performClick()
        waitForIdle()
        assertTrue(app.exited)
    }

    @Test
    fun `quitting with nothing unsaved quits at once`() = runComposeUiTest {
        val app = launch()
        runOnIdle { app.exitRequested = true }
        waitForIdle()
        assertTrue(app.exited)
    }
}
