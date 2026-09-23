package com.sherlog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The strip in a deliberately narrow window: tabs share the width, shrink as
 * more open, then stop shrinking and scroll.
 */
@OptIn(ExperimentalTestApi::class)
class TabStripUiTest {

    private val dir: File = Files.createTempDirectory("tabstrip").toFile()
    private var addClicks = 0

    private fun logs(count: Int): List<File> = (0 until count).map { i ->
        File(dir, "log_%02d.txt".format(i)).apply {
            writeText("07-12 10:00:%02d.000  1000  1000 I Strip: file %02d\n".format(i % 60, i))
        }
    }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun ComposeUiTest.launch(files: List<File>): Workspace {
        lateinit var workspace: Workspace
        setContent {
            val scope = rememberCoroutineScope()
            workspace = remember { Workspace(scope) }
            MaterialTheme {
                App(workspace, onOpenFiles = { addClicks++ }, onOpenFolder = {}, onExportClick = {}, onCopyText = {})
            }
        }
        runOnIdle { workspace.open(files) }
        waitUntil(timeoutMillis = 20_000) { workspace.tabs.all { it.index != null && !it.isLoading } }
        waitForIdle()
        return workspace
    }

    /** The chip for [name] — the first match, since the top bar also shows the active file. */
    private fun ComposeUiTest.chipWidth(name: String): Float =
        onAllNodesWithText(name)[0].fetchSemanticsNode().size.width.toFloat()

    @Test
    fun `tabs shrink as more open, down to a floor`() = runDesktopComposeUiTest(900, 600) {
        val few = launch(logs(3))
        val wide = chipWidth("log_00.txt")

        runOnIdle { few.open(logs(8).drop(3)) }
        waitUntil(timeoutMillis = 20_000) { few.tabs.size == 8 && few.tabs.all { it.index != null } }
        waitForIdle()
        val narrow = chipWidth("log_00.txt")
        assertTrue(narrow < wide, "8 tabs should be narrower than 3 (was $narrow vs $wide)")

        runOnIdle { few.open(logs(14).drop(8)) }
        waitUntil(timeoutMillis = 30_000) { few.tabs.size == 14 && few.tabs.all { it.index != null } }
        waitForIdle()
        // Past the floor they stop shrinking and the strip scrolls instead.
        assertEquals(narrow.toInt(), chipWidth("log_00.txt").toInt(), "tabs shrank past the floor")
    }

    @Test
    fun `the active tab is scrolled into view, and + stays reachable`() = runDesktopComposeUiTest(900, 600) {
        val ws = launch(logs(14))
        // The last tab is off the end of a 900 px strip at the width floor.
        assertFalse(runCatching { onAllNodesWithText("log_13.txt")[0].assertIsDisplayed() }.isSuccess)

        repeat(13) { press(Key.Tab, ctrl = true) }
        waitForIdle()
        assertEquals("log_13.txt", ws.active.file?.name)
        onAllNodesWithText("log_13.txt")[0].assertIsDisplayed()

        onNodeWithText("+").performClick()
        assertEquals(1, addClicks)
    }

    private fun ComposeUiTest.press(key: Key, ctrl: Boolean = false) {
        onRoot().performKeyInput {
            if (ctrl) withKeyDown(Key.CtrlLeft) { pressKey(key) } else pressKey(key)
        }
        waitForIdle()
    }
}
