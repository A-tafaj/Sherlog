package com.sherlog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.sherlog.filter.FilterMode
import java.io.File
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The File menu, Save's confirmation and the file shortcuts, driven through the real [App]. */
@OptIn(ExperimentalTestApi::class)
class FileMenuUiTest {

    private val lines = (0 until 40).map {
        val tag = if (it % 2 == 0) "Keep" else "Noise"
        "07-12 10:00:%02d.000  1000  1000 I %s: line %03d".format(it, tag, it)
    }
    private val file = File.createTempFile("filemenuui", ".txt").apply { writeText(lines.joinToString("\n", postfix = "\n")) }
    private val calls = Collections.synchronizedList(mutableListOf<String>())

    @AfterTest
    fun cleanup() {
        file.delete()
    }

    private fun ComposeUiTest.launch(): Workspace {
        lateinit var workspace: Workspace
        setContent {
            val scope = rememberCoroutineScope()
            workspace = remember { Workspace(scope) }
            MaterialTheme {
                App(
                    workspace,
                    onOpenFiles = { calls += "open files" },
                    onOpenFolder = { calls += "open folder" },
                    onExportClick = { calls += "export" },
                    onCopyText = {},
                    onSaveAs = { calls += "save as" },
                )
            }
        }
        runOnIdle { workspace.open(listOf(file)) }
        waitUntil(timeoutMillis = 10_000) { workspace.active.filteredLines.size == lines.size && !workspace.active.isLoading }
        waitForIdle()
        return workspace
    }

    private fun ComposeUiTest.hideNoise(ws: Workspace) {
        runOnIdle {
            ws.active.selectedTags = setOf("Noise")
            ws.active.tagMode = FilterMode.HIDE
            ws.active.scheduleApply(0)
        }
        waitUntil(timeoutMillis = 10_000) { ws.active.filteredLines.size == 20 && !ws.active.isBusy }
        waitForIdle()
    }

    private fun ComposeUiTest.ctrl(key: Key, shift: Boolean = false) {
        onRoot().performKeyInput {
            withKeyDown(Key.CtrlLeft) { if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key) }
        }
        waitForIdle()
    }

    @Test
    fun `the file menu opens files and folders, and save waits for something hidden`() = runComposeUiTest {
        val ws = launch()
        onNodeWithText("File ▾").performClick()
        onNodeWithText("Save").assertIsNotEnabled() // nothing hidden yet
        onNodeWithText("Save As…").assertIsEnabled()
        onNodeWithText("Open Folder…").performClick()
        waitForIdle()
        assertEquals(listOf("open folder"), calls)

        hideNoise(ws)
        onNodeWithText("File ▾").performClick()
        onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun `ctrl+s asks first, and cancel leaves the file alone`() = runComposeUiTest {
        val ws = launch()
        hideNoise(ws)
        val before = file.readBytes()
        ctrl(Key.S)
        onNodeWithText("Only the 20 lines in view are kept. The 20 hidden lines will be removed from the file for good.")
            .assertExists()
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertContentEquals(before, file.readBytes())
        // The dialog is gone and shortcuts work again.
        ctrl(Key.B)
        assertTrue(ws.prefs.filterPanelCollapsed)
    }

    @Test
    fun `confirming the save rewrites the file with the lines in view`() = runComposeUiTest {
        val ws = launch()
        hideNoise(ws)
        ctrl(Key.S)
        onNodeWithText("Save").performClick() // exact text: not "Save As…" or the title
        waitUntil(timeoutMillis = 10_000) { !ws.active.isLoading && ws.active.index?.lineCount == 20 }
        assertEquals(lines.filterIndexed { i, _ -> i % 2 == 0 }, file.readLines())
        assertEquals(listOf("Keep"), ws.active.index!!.tags.toList())
        assertEquals(0, ws.active.activeFilterCount)
    }

    @Test
    fun `save as from the confirmation, and the file shortcuts`() = runComposeUiTest {
        val ws = launch()
        hideNoise(ws)
        ctrl(Key.S)
        onNodeWithText("Save As…").performClick()
        waitForIdle()
        assertEquals(listOf("save as"), calls)
        calls.clear()

        ctrl(Key.S, shift = true)
        ctrl(Key.O)
        ctrl(Key.O, shift = true)
        assertEquals(listOf("save as", "open files", "open folder"), calls)
    }

    @Test
    fun `export is an icon now, and still exports`() = runComposeUiTest {
        launch()
        onNodeWithContentDescription("Export Filtered").performClick()
        waitForIdle()
        assertEquals(listOf("export"), calls)
        assertFalse(calls.contains("save as"))
    }
}
