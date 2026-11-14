package com.sherlog

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sherlog.core.LogFiles
import com.sherlog.ui.App
import com.sherlog.ui.UiPrefs
import com.sherlog.ui.Workspace
import java.awt.Component
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.UIManager

fun main() = application {
    // Closing the window first asks about tabs with unsaved changes (App).
    var exitRequested by remember { mutableStateOf(false) }
    Window(
        onCloseRequest = { exitRequested = true },
        title = "Sherlog",
        // Taskbar/window icon while running. The installers get their icon
        // from icons/ instead (see build.gradle.kts) — jpackage needs
        // platform-specific containers, which this SVG is the source for.
        icon = painterResource("logo.svg"),
        state = rememberWindowState(width = 1400.dp, height = 900.dp),
    ) {
        val scope = rememberCoroutineScope()
        val workspace = remember { Workspace(scope, UiPrefs.persistent()) }
        // The default scrollbar blends into the dark theme, so it is lightened
        // — but kept restrained, since it sits beside dense log text and is
        // the only hint that a region scrolls at all.
        val scrollbarStyle = defaultScrollbarStyle().copy(
            unhoverColor = Color.White.copy(alpha = 0.3f),
            hoverColor = Color.White.copy(alpha = 0.6f),
        )
        MaterialTheme(colorScheme = darkColorScheme()) {
            CompositionLocalProvider(LocalScrollbarStyle provides scrollbarStyle) {
                App(
                    workspace = workspace,
                    onOpenFiles = {
                        val files = chooseLogFiles(window)
                        if (files.isNotEmpty()) workspace.open(files)
                    },
                    onOpenFolder = {
                        val start = workspace.active.file?.parentFile
                        chooseFolder(window, start)?.let(workspace::openFolder)
                    },
                    onExportClick = {
                        // Default to a name derived from the source so a re-export
                        // doesn't collide with the file currently open. An
                        // Untitled tab's file is a temp file: neither its name
                        // nor its folder is the user's.
                        val tab = workspace.active
                        val source = if (tab.isUntitled) File("${tab.displayName}.txt") else tab.index?.file
                        val folder = if (tab.isUntitled) null else source?.parentFile
                        chooseTextFileToSave(window, "Export Filtered Logs", LogFiles.exportName(source), folder)
                            ?.let(workspace::exportActive)
                    },
                    exitRequested = exitRequested,
                    onExit = ::exitApplication,
                    onExitCancelled = { exitRequested = false },
                    onSaveAs = { then ->
                        // Like any editor, Save As starts from the file's own name.
                        val tab = workspace.active
                        val name = if (tab.isUntitled) "${tab.displayName}.txt" else tab.file?.name ?: "untitled.txt"
                        val folder = if (tab.isUntitled) null else tab.file?.parentFile
                        val target = chooseTextFileToSave(window, "Save As", name, folder)
                        if (target == null) then(false) else workspace.saveActiveAs(target, then)
                    },
                    onCopyText = { text ->
                        // Arrives from a worker thread (the lines are read off
                        // the UI thread); the clipboard belongs to the UI one.
                        EventQueue.invokeLater {
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                        }
                    },
                )
            }
        }
    }
}

/**
 * The native file picker, allowing several files at once. It lists every
 * file: rotated logs (`logcat.01`) have no `.txt`/`.log`, and
 * [Workspace.open] turns binaries away itself.
 */
private fun chooseLogFiles(parent: Frame): List<File> {
    val dialog = FileDialog(parent, "Open Log Files", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files.toList()
}

/**
 * The native save dialog, for a text file. Windows' AWT dialog only offers
 * "All Files", so a name typed without an extension would be saved without
 * one; [LogFiles.withTextExtension] adds `.txt`. The dialog asked about
 * replacing the name as typed, not the one with `.txt` added, so that one
 * is confirmed here.
 */
private fun chooseTextFileToSave(parent: Frame, title: String, suggestedName: String, startDir: File?): File? {
    val dialog = FileDialog(parent, title, FileDialog.SAVE)
    startDir?.let { dialog.directory = it.absolutePath }
    dialog.file = suggestedName
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    val chosen = File(dir, name)
    val target = LogFiles.withTextExtension(chosen)
    if (target != chosen && target.exists()) {
        useSystemLookAndFeel()
        val answer = JOptionPane.showConfirmDialog(
            parent,
            "${target.name} already exists.\nDo you want to replace it?",
            title,
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        if (answer != JOptionPane.YES_OPTION) return null
    }
    return target
}

/**
 * A folder picker. AWT's native dialog can't pick folders on Windows, so this
 * is Swing's — given the system look, so it doesn't stand out as foreign.
 */
private fun chooseFolder(parent: Component, start: File?): File? {
    useSystemLookAndFeel()
    val chooser = JFileChooser(start).apply {
        dialogTitle = "Open Folder"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** Swing dialogs (folder picker, confirmations) in the system look, so they don't stand out as foreign. */
private fun useSystemLookAndFeel() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
}
