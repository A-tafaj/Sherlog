package com.sherlog

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
        // Taskbar/window icon while running. The installers get their icon
        // from icons/ instead (see build.gradle.kts) — jpackage needs
        // platform-specific containers, which this SVG is the source for.
        icon = painterResource("logo.svg"),
        state = rememberWindowState(width = 1400.dp, height = 900.dp),
    ) {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(scope) }
        // The default scrollbar blends into the dark theme; make it clearly visible.
        val scrollbarStyle = defaultScrollbarStyle().copy(
            unhoverColor = Color.White.copy(alpha = 0.5f),
            hoverColor = Color.White.copy(alpha = 0.8f),
        )
        MaterialTheme(colorScheme = darkColorScheme()) {
            CompositionLocalProvider(LocalScrollbarStyle provides scrollbarStyle) {
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
                        // Default to a name derived from the source so a re-export
                        // doesn't collide with the file currently open.
                        dialog.file = state.index?.file?.nameWithoutExtension
                            ?.let { "${it}_filtered.txt" } ?: "cleaned_logcat.txt"
                        dialog.isVisible = true
                        val dir = dialog.directory
                        val name = dialog.file
                        if (dir != null && name != null) state.export(File(dir, name))
                    },
                )
            }
        }
    }
}
