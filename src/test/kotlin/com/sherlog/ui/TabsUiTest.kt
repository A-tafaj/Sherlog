package com.sherlog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Drives the real [App] headlessly with two files open in tabs. */
@OptIn(ExperimentalTestApi::class)
class TabsUiTest {

    private val dir: File = Files.createTempDirectory("tabsui").toFile()

    private fun log(name: String, word: String, lines: Int): File = File(dir, name).apply {
        writeText((0 until lines).joinToString("\n", postfix = "\n") { i ->
            "07-12 10:%02d:%02d.000  1000  1000 I TabTest: %s %03d".format(i / 60, i % 60, word, i)
        })
    }

    private val fileA = log("alpha.txt", "alpha", 200)
    private val fileB = log("bravo.txt", "bravo", 50)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private var addClicks = 0

    private fun ComposeUiTest.launchWithTwoTabs(): Workspace {
        lateinit var workspace: Workspace
        setContent {
            val scope = rememberCoroutineScope()
            workspace = remember { Workspace(scope) }
            MaterialTheme {
                App(workspace, onOpenFiles = { addClicks++ }, onOpenFolder = {}, onExportClick = {}, onCopyText = {})
            }
        }
        runOnIdle { workspace.open(listOf(fileA, fileB)) }
        waitUntil(timeoutMillis = 10_000) { workspace.tabs.all { it.index != null && !it.isLoading } }
        waitForIdle()
        return workspace
    }

    private fun ComposeUiTest.row(text: String) = onNode(hasText(text, substring = true))

    private fun ComposeUiTest.rowIsShown(text: String) = runCatching { row(text).assertIsDisplayed() }.isSuccess

    /** The tab for [name]; the top bar also shows the active file's name, so the tab is the first match. */
    private fun ComposeUiTest.tab(name: String) = onAllNodesWithText(name)[0]

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

    @Test
    fun `each open file gets a tab, and clicking one shows that file`() = runComposeUiTest {
        val ws = launchWithTwoTabs()
        assertEquals(fileA, ws.active.file)
        row("alpha 000").assertIsDisplayed()

        tab("bravo.txt").performClick()
        waitForIdle()
        assertEquals(fileB, ws.active.file)
        row("bravo 000").assertIsDisplayed()
        assertEquals(false, rowIsShown("alpha 000"))
    }

    @Test
    fun `ctrl+tab cycles tabs and ctrl+w closes the current one`() = runComposeUiTest {
        val ws = launchWithTwoTabs()
        press(Key.Tab, ctrl = true)
        assertEquals(fileB, ws.active.file)
        press(Key.Tab, ctrl = true, shift = true)
        assertEquals(fileA, ws.active.file)

        press(Key.W, ctrl = true)
        assertEquals(1, ws.tabs.size)
        assertEquals(fileB, ws.active.file)
        row("bravo 000").assertIsDisplayed()
    }

    @Test
    fun `shortcuts keep working after switching away from a focused row`() = runComposeUiTest {
        val ws = launchWithTwoTabs()
        row("alpha 003").performClick() // gives that row's text field focus
        waitForIdle()
        press(Key.Tab, ctrl = true) // alpha's rows, the focused one included, are gone
        assertEquals(fileB, ws.active.file)
        press(Key.Tab, ctrl = true)
        assertEquals(fileA, ws.active.file)
    }

    @Test
    fun `the tab's close button closes it`() = runComposeUiTest {
        val ws = launchWithTwoTabs()
        onAllNodesWithText("×")[1].performClick() // bravo's
        waitForIdle()
        assertEquals(listOf(fileA), ws.tabs.map { it.file })
    }

    @Test
    fun `plus asks for more files`() = runComposeUiTest {
        launchWithTwoTabs()
        onNodeWithText("+").performClick()
        assertEquals(1, addClicks)
    }

    @Test
    fun `each tab keeps its own scroll position`() = runComposeUiTest {
        val ws = launchWithTwoTabs()
        onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("alpha", substring = true)))
            .performScrollToIndex(120)
        waitForIdle()
        row("alpha 120").assertIsDisplayed()

        tab("bravo.txt").performClick()
        waitForIdle()
        row("bravo 000").assertIsDisplayed()

        tab("alpha.txt").performClick()
        waitForIdle()
        assertEquals(fileA, ws.active.file)
        row("alpha 120").assertIsDisplayed()
    }

    @Test
    fun `apply filters to all tabs copies this tab's filters to the others`() = runComposeUiTest {
        val ws = launchWithTwoTabs()
        val (alpha, bravo) = ws.tabs
        runOnIdle {
            alpha.includeText = "010, 020"
            alpha.scheduleApply(0)
        }
        onNodeWithText("Apply filters to all tabs").assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 10_000) { bravo.filteredLines.size == 2 }
        assertEquals("010, 020", bravo.includeText)
        assertSame(alpha, ws.active) // the source tab stays shown

        tab("bravo.txt").performClick()
        waitForIdle()
        row("bravo 010").assertIsDisplayed()
        assertEquals(false, rowIsShown("bravo 011"))
    }
}
