package com.sherlog

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sherlog.ui.App
import com.sherlog.ui.AppState
import java.awt.FileDialog
import java.io.File

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Sherlog",
        state = rememberWindowState(width = 1400.dp, height = 900.dp),
    ) {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(scope) }
        MaterialTheme(colorScheme = darkColorScheme()) {
            App(
                state = state,
                onOpenClick = {
                    val dialog = FileDialog(window, "Open Log File", FileDialog.LOAD)
                    dialog.file = "*.txt;*.log"
                    dialog.isVisible = true
                    val file = dialog.files.firstOrNull()
                    if (file != null) state.openFile(file)
                },
                onExportClick = {
                    val dialog = FileDialog(window, "Export Filtered Logs", FileDialog.SAVE)
                    dialog.file = "cleaned_logcat.txt"
                    dialog.isVisible = true
                    val dir = dialog.directory
                    val name = dialog.file
                    if (dir != null && name != null) state.export(File(dir, name))
                },
            )
        }
    }
}
