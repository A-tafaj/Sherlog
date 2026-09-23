package com.sherlog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Ctrl+Shift+F and the results panel, driven through the real [App]. */
@OptIn(ExperimentalTestApi::class)
class TabSearchUiTest {

    private val dir: File = Files.createTempDirectory("tabsearchui").toFile()

    private fun log(name: String, marker: String, markedLines: Int, plainLines: Int): File =
        File(dir, name).apply {
            val lines = buildList {
                repeat(markedLines) { add("07-12 10:00:%02d.000  1000  1000 I Tag: %s %02d".format(it, marker, it)) }
                repeat(plainLines) { add("07-12 10:01:%02d.000  1000  1000 I Tag: quiet %02d".format(it, it)) }
            }
            writeText(lines.joinToString("\n", postfix = "\n"))
        }

    private val fileA = log("alpha.txt", "TOKEN alpha", markedLines = 3, plainLines = 40)
    private val fileB = log("bravo.txt", "TOKEN bravo", markedLines = 1, plainLines = 10)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun ComposeUiTest.launch(): Workspace {
        lateinit var workspace: Workspace
        setContent {
            val scope = rememberCoroutineScope()
            workspace = remember { Workspace(scope) }
            MaterialTheme { App(workspace, onOpenFiles = {}, onOpenFolder = {}, onExportClick = {}, onCopyText = {}) }
        }
        runOnIdle { workspace.open(listOf(fileA, fileB)) }
        waitUntil(timeoutMillis = 10_000) { workspace.tabs.all { it.index != null && !it.isLoading } }
        waitForIdle()
        return workspace
    }

    private fun ComposeUiTest.press(key: Key, ctrl: Boolean = false, shift: Boolean = false) {
        onRoot().performKeyInput {
            when {
                ctrl && shift -> withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(key) } }
                ctrl -> withKeyDown(Key.CtrlLeft) { pressKey(key) }
                else -> pressKey(key)
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.searchAllTabs(ws: Workspace, query: String) {
        press(Key.F, ctrl = true, shift = true)
        onNodeWithText("Search all tabs…").performTextInput(query)
        waitForIdle()
        press(Key.Enter)
        waitUntil(timeoutMillis = 10_000) { !ws.search.isSearching && ws.search.resultsQuery == query }
        waitForIdle()
    }

    @Test
    fun `ctrl+shift+F searches every tab and groups the results by file`() = runComposeUiTest {
        val ws = launch()
        searchAllTabs(ws, "TOKEN")

        onNodeWithText("alpha.txt (3)").assertIsDisplayed()
        onNodeWithText("bravo.txt (1)").assertIsDisplayed()
        onNodeWithText("4 matches in 2 files").assertIsDisplayed()
        // The tab's own search box is untouched — this is the Ctrl+F guard.
        assertEquals("", ws.active.searchText)
    }

    @Test
    fun `clicking a result shows that line in its own tab`() = runComposeUiTest {
        val ws = launch()
        val alpha = ws.tabs[0]
        val bravo = ws.tabs[1]
        searchAllTabs(ws, "TOKEN")
        assertSame(alpha, ws.active)

        // bravo's single hit; result rows carry the line number, so this node
        // is distinct from the same text in the viewer.
        // Only the results panel shows bravo's lines — alpha is the tab on screen.
        onNodeWithText("TOKEN bravo 00", substring = true).performClick()
        waitForIdle()

        assertSame(bravo, ws.active)
        assertEquals(0, bravo.listState.firstVisibleItemIndex)
        assertEquals("TOKEN", bravo.selectionHighlight)
    }

    @Test
    fun `escape closes the panel first, then peels the tab's own state`() = runComposeUiTest {
        val ws = launch()
        searchAllTabs(ws, "TOKEN")
        assertTrue(ws.search.isOpen)

        press(Key.Escape)
        assertFalse(ws.search.isOpen)
        onNodeWithText("alpha.txt (3)").assertDoesNotExist()

        // Shortcuts still work after the panel took and gave back focus.
        press(Key.Tab, ctrl = true)
        assertSame(ws.tabs[1], ws.active)
    }
}
