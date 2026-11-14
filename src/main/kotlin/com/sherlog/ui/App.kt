package com.sherlog.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlog.filter.Preset
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun App(
    workspace: Workspace,
    onOpenFiles: () -> Unit,
    onOpenFolder: () -> Unit,
    onExportClick: () -> Unit,
    /** Puts text on the system clipboard (a platform concern, like the file dialogs). */
    onCopyText: (String) -> Unit,
    /** Asks where to Save As (a native dialog), then saves there. Save itself needs no dialog. */
    onSaveAs: () -> Unit = {},
    /** The window's close button was pressed: unsaved tabs get asked about before [onExit]. */
    exitRequested: Boolean = false,
    onExit: () -> Unit = {},
    /** The user chose Cancel on one of those questions; the app stays open. */
    onExitCancelled: () -> Unit = {},
) {
    // Everything below shows the active tab.
    val state = workspace.active
    // The tab's own scroll position; hoisted here so the status-bar next/prev
    // buttons can scroll the viewer.
    val listState = state.listState
    val scope = rememberCoroutineScope()
    val searchFocus = remember { FocusRequester() }
    // Holds keyboard focus so shortcuts work before the user clicks anything;
    // clicking a field moves focus away, but onPreviewKeyEvent still tunnels
    // through this root first.
    val rootFocus = remember { FocusRequester() }
    // Again on every tab switch: the previous tab's widgets are gone, and if
    // one of them held focus, key events would have nowhere to go.
    LaunchedEffect(state) { rootFocus.requestFocus() }

    // The scroll a next/prev jump started. Until it lands, the screen isn't
    // showing where navigation is heading, so rapid presses must step from
    // the match being jumped to rather than re-anchor on a half-scrolled view.
    var matchJump by remember(state) { mutableStateOf<Job?>(null) }
    // A jump waiting for the match count to finish, and whether the search
    // box has focus (Enter only navigates from there).
    var pendingJump by remember(state) { mutableStateOf<Job?>(null) }
    var searchFocused by remember(state) { mutableStateOf(false) }
    // Whether the root itself holds focus (nothing clicked yet, or just after
    // a tab switch), as opposed to a field or row inside it.
    var rootFocused by remember { mutableStateOf(false) }
    // Whether focus is anywhere in the window at all.
    var focusInside by remember { mutableStateOf(false) }

    // Save asks first when the filters hide lines, naming how many: the file
    // loses them for good. Edits alone save straight away, as in an editor.
    var confirmingSave by remember(state) { mutableStateOf(false) }
    fun requestSave() {
        if (!state.canSave) return
        if (state.hiddenLineCount > 0) confirmingSave = true else state.save()
    }

    // Tabs with unsaved changes that are being closed, asked about one at a
    // time, and whether the app quits once they're all answered.
    var closing by remember { mutableStateOf<List<AppState>>(emptyList()) }
    var quitting by remember { mutableStateOf(false) }
    var savingBeforeClose by remember { mutableStateOf(false) }

    /** Closes [tabs]; the ones with unsaved changes are asked about first. */
    fun closeTabs(tabs: List<AppState>, quit: Boolean) {
        val (dirty, clean) = tabs.partition { it.isDirty }
        if (!quit) clean.forEach(workspace::close)
        quitting = quit
        closing = dirty
        if (dirty.isEmpty() && quit) onExit()
    }

    /** The tab asked about is answered: close it (unless quitting) and ask about the next. */
    fun closeAnswered(tab: AppState) {
        if (!quitting) workspace.close(tab)
        closing = closing.drop(1)
        if (closing.isEmpty() && quitting) onExit()
    }

    fun cancelClosing() {
        closing = emptyList()
        if (quitting) onExitCancelled()
        quitting = false
    }

    LaunchedEffect(exitRequested) {
        if (exitRequested) closeTabs(workspace.tabs, quit = true)
    }
    // Show the file being asked about.
    LaunchedEffect(closing.firstOrNull()) {
        closing.firstOrNull()?.let(workspace::select)
    }

    // Folding the filter panel away disposes a field that may hold focus, and
    // with nothing focused no shortcut fires (not even Ctrl+B to undo it). A
    // closed dialog, or a deleted line that had the caret, can leave focus
    // nowhere the same way.
    LaunchedEffect(workspace.prefs.filterPanelCollapsed, confirmingSave, closing, state.filteredLines) {
        withFrameNanos { }
        if (!focusInside) runCatching { rootFocus.requestFocus() }
    }

    // The results panel's query field. Hoisted here (not remembered inside the
    // panel, which every tab switch re-creates) and focused from one effect, so
    // it can't race the root-focus effect above.
    val resultsFocus = remember { FocusRequester() }
    LaunchedEffect(workspace.search.isOpen) {
        if (!workspace.search.isOpen) return@LaunchedEffect
        // The panel only composes on the next frame; asking before it exists
        // throws and leaves the query box without the cursor.
        withFrameNanos { }
        runCatching { resultsFocus.requestFocus() }
    }

    /** Closing must hand focus back, or no shortcut fires afterwards. */
    fun closeResults() {
        workspace.search.close()
        runCatching { rootFocus.requestFocus() }
    }

    /** Positions in [AppState.filteredLines] on screen — or, mid-jump, the match being jumped to. */
    fun visiblePositions(): IntRange {
        val target = state.currentMatchPosition
        if (matchJump?.isActive == true && target >= 0) return target..target
        val items = listState.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return listState.firstVisibleItemIndex.let { it..it }
        return items.first().index..items.last().index
    }

    /**
     * Scrolls to a match only when it isn't fully on screen already, so
     * stepping through the matches in view keeps the view still.
     */
    fun revealMatch(pos: Int?) {
        if (pos == null) return
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == pos }
        val onScreen = item != null && item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset
        if (onScreen && matchJump?.isActive != true) return
        matchJump = scope.launch { listState.animateScrollToItem(pos) }
    }

    /**
     * Steps to the next/previous match. While the match list is still being
     * counted — a pass over the file, seconds on a huge log — the jump waits
     * for it, so an Enter pressed straight after Ctrl+F isn't swallowed.
     */
    fun navigateMatch(forward: Boolean) {
        fun jump() = revealMatch(
            if (forward) state.nextMatch(visiblePositions()) else state.prevMatch(visiblePositions()),
        )
        pendingJump?.cancel()
        if (!state.highlightCounting) {
            jump()
            return
        }
        pendingJump = scope.launch {
            // Bounded, so a count that never lands can't leave a poll running.
            withTimeoutOrNull(30_000) { while (state.highlightCounting) delay(50) }
            jump()
        }
    }

    /** Undoes (or redoes) one edit and shows the line it changed, if that's off screen. */
    fun undo(redo: Boolean) {
        val line = (if (redo) state.redo() else state.undo()) ?: return
        val position = state.filteredLines.binarySearch(line)
        if (position >= 0) revealMatch(position)
    }

    fun onShortcut(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val exportEnabled = state.canWriteView
        return when {
            // With whole lines selected, Ctrl+C is theirs; otherwise it falls
            // through to the focused text field's own copy.
            e.isCtrlPressed && e.key == Key.C && state.lineSelection != null -> {
                state.copySelectedLines(onCopyText); true
            }
            // Only while no field has focus: in a text box, Ctrl+A is the box's.
            // From a clicked row, the viewer takes it (LogViewer).
            rootFocused && e.isCtrlPressed && e.key == Key.A -> { state.selectAllLines(); true }
            // Undo is the log's only from here or the viewer (LogViewer); in a
            // text box, Ctrl+Z undoes typing in that box.
            rootFocused && e.isCtrlPressed && e.key == Key.Z && !e.isShiftPressed -> { undo(redo = false); true }
            rootFocused && e.isCtrlPressed && (e.key == Key.Y || (e.key == Key.Z && e.isShiftPressed)) -> {
                undo(redo = true); true
            }
            // After Ctrl+A from here, focus is still on the root, not in the viewer.
            rootFocused && state.lineSelection != null && (e.key == Key.Delete || e.key == Key.Backspace) -> {
                state.lineSelection?.let(state::deleteLines); true
            }
            // Log text zoom, for every tab. Ctrl+= is Ctrl++ without Shift.
            e.isCtrlPressed && e.key in ZoomInKeys -> { workspace.prefs.zoom(1); true }
            e.isCtrlPressed && e.key in ZoomOutKeys -> { workspace.prefs.zoom(-1); true }
            e.isCtrlPressed && e.key in ZoomResetKeys -> { workspace.prefs.resetZoom(); true }
            e.isCtrlPressed && e.key == Key.B -> { workspace.prefs.toggleFilterPanel(); true }
            // Ctrl+Shift+F searches every open tab. It must come first: the
            // Ctrl+F branch below doesn't look at Shift and would swallow it.
            e.isCtrlPressed && e.isShiftPressed && e.key == Key.F -> {
                workspace.search.open(
                    seed = state.selectionHighlight.ifEmpty { state.searchText },
                    regex = state.searchIsRegex,
                )
                true
            }
            // Ctrl+F searches for whatever is selected in a line, so the text
            // doesn't have to be copied into the box by hand.
            e.isCtrlPressed && e.key == Key.F -> { state.useSelectionAsSearch(); searchFocus.requestFocus(); true }
            // Enter is the search box's own: elsewhere it belongs to whatever has focus.
            searchFocused && e.key == Key.Enter -> { navigateMatch(forward = !e.isShiftPressed); true }
            // The Shift variants first: the plain ones don't look at Shift.
            e.isCtrlPressed && e.isShiftPressed && e.key == Key.O -> { onOpenFolder(); true }
            e.isCtrlPressed && e.key == Key.O -> { onOpenFiles(); true }
            e.isCtrlPressed && e.isShiftPressed && e.key == Key.S -> { if (exportEnabled) onSaveAs(); true }
            e.isCtrlPressed && e.key == Key.S -> { requestSave(); true }
            e.isCtrlPressed && e.key == Key.E -> { if (exportEnabled) onExportClick(); exportEnabled }
            e.isCtrlPressed && e.key == Key.W -> { if (state.file != null) closeTabs(listOf(state), quit = false); true }
            e.isCtrlPressed && e.key == Key.Tab -> { workspace.selectNext(if (e.isShiftPressed) -1 else 1); true }
            e.isCtrlPressed && e.key == Key.PageDown -> { workspace.selectNext(1); true }
            e.isCtrlPressed && e.key == Key.PageUp -> { workspace.selectNext(-1); true }
            e.key == Key.F3 -> { navigateMatch(forward = !e.isShiftPressed); true }
            // The results panel is the topmost transient layer, so Esc takes it
            // first; a second Esc peels the tab's own selection/search/highlight.
            e.key == Key.Escape -> if (workspace.search.isOpen) { closeResults(); true } else state.onEscape()
            else -> false
        }
    }

    Surface(
        Modifier.fillMaxSize()
            .focusRequester(rootFocus)
            .onFocusChanged {
                rootFocused = it.isFocused
                focusInside = it.hasFocus
            }
            .onPreviewKeyEvent(::onShortcut)
            .focusTarget(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // The window's height is what keeps the results panel from starving
        // the viewer; captured here because Column shadows the receiver.
        BoxWithConstraints {
        val windowHeight = maxHeight
        val windowWidth = maxWidth
        Column {
            TabStrip(workspace, onAddFiles = onOpenFiles, onCloseTab = { closeTabs(listOf(it), quit = false) })
            // Keyed on the tab so UI-only state remembered below (open menus,
            // the tag list's scroll, the slider) belongs to one tab rather
            // than leaking into the next.
            key(state) {
                TopBar(
                    workspace,
                    onOpenFiles = onOpenFiles,
                    onOpenFolder = onOpenFolder,
                    onSave = { requestSave() },
                    onSaveAs = onSaveAs,
                    onExportClick = onExportClick,
                )
                if (confirmingSave) {
                    SaveConfirmation(
                        state,
                        onSave = { confirmingSave = false; state.save() },
                        onSaveAs = { confirmingSave = false; onSaveAs() },
                        onCancel = { confirmingSave = false },
                    )
                }
                val asking = closing.firstOrNull()
                if (asking != null && asking === state && !savingBeforeClose) {
                    UnsavedChangesDialog(
                        asking,
                        onSave = {
                            savingBeforeClose = true
                            asking.save { saved ->
                                savingBeforeClose = false
                                // A failed save says why in the status bar; nothing closes.
                                if (saved) closeAnswered(asking) else cancelClosing()
                            }
                        },
                        onDiscard = { closeAnswered(asking) },
                        onCancel = { cancelClosing() },
                    )
                }
                HorizontalDivider()
                Row(Modifier.weight(1f)) {
                    // Never more than ~half the window, so the log keeps the room.
                    FilterSidePanel(state, workspace.prefs, maxWidth = windowWidth * 0.45f)
                    Column(Modifier.weight(1f)) {
                        SearchBar(state, searchFocus) { searchFocused = it }
                        HorizontalDivider()
                        val index = state.index
                        val provider = state.provider
                        when {
                            index == null || provider == null ->
                                EmptyViewer(state.statusMessage, Modifier.weight(1f).fillMaxWidth())
                            state.filteredLines.isEmpty() -> {
                                val active = state.activeFilterCount
                                EmptyViewer(
                                    if (active > 0) {
                                        "No lines match the current filters " +
                                            "($active active). Clear Filters resets them all."
                                    } else "This file has no lines.",
                                    Modifier.weight(1f).fillMaxWidth(),
                                )
                            }
                            else -> LogViewer(
                                index = index,
                                provider = provider,
                                filteredLines = state.filteredLines,
                                searchQuery = state.appliedFilter.searchQuery,
                                searchIsRegex = state.appliedFilter.searchIsRegex,
                                highlightNeedle = state.highlightNeedle,
                                highlightIsRegex = state.highlightIsRegex,
                                currentMatchPosition = state.currentMatchPosition,
                                onSelectionChange = { line, text -> state.onViewerSelection(line, text) },
                                lineSelection = state.lineSelection,
                                onLinePressed = { state.onLinePressed(it) },
                                onSelectLines = { from, to -> state.selectLines(from, to) },
                                onExtendLineSelection = { state.extendLineSelection(it) },
                                onCopyLines = { state.copySelectedLines(onCopyText) },
                                onSelectAll = { state.selectAllLines() },
                                textScale = workspace.prefs.textScale,
                                onZoom = { workspace.prefs.zoom(it) },
                                // Stale while recounting: they may index the previous view.
                                matchPositions = if (state.highlightCounting) IntArray(0) else state.highlightMatches,
                                currentMatchIndex = state.currentMatchIndex,
                                onMatchClick = { revealMatch(state.goToMatch(it)) },
                                edits = state.editedLines,
                                onEditLine = state::editLine,
                                onDeleteLines = state::deleteLines,
                                onJoinPrevious = state::joinWithPrevious,
                                onJoinNext = state::joinWithNext,
                                onUndo = { undo(redo = false) },
                                onRedo = { undo(redo = true) },
                                listState = listState,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                }
                if (workspace.search.isOpen) {
                    // A fixed-height sibling of the weighted viewer is the very
                    // shape that once squeezed the tag list to zero height, so
                    // the panel is clamped against the window.
                    PanelResizeHandle(workspace.search)
                    SearchResultsPanel(
                        workspace,
                        queryFocus = resultsFocus,
                        onClose = { closeResults() },
                        modifier = Modifier.height(
                            workspace.search.height.coerceIn(120.dp, windowHeight * 0.55f),
                        ),
                    )
                }
                HorizontalDivider()
                StatusBar(
                    state,
                    workspace.prefs,
                    onPrevMatch = { revealMatch(state.prevMatch(visiblePositions())) },
                    onNextMatch = { revealMatch(state.nextMatch(visiblePositions())) },
                    onClearMatch = { state.clearHighlight() },
                    onCopyLines = { state.copySelectedLines(onCopyText) },
                )
            }
        }
        }
    }
}

/**
 * One tab per open file, Sublime-style, with + to open more. The blank tab
 * that stands in while nothing is open gets no tab of its own.
 */

@Composable
internal fun TooltipText(text: String) {
    Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(4.dp)) {
        Text(
            text,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            fontSize = 11.sp,
            modifier = Modifier.widthIn(max = 360.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TopBar(
    workspace: Workspace,
    onOpenFiles: () -> Unit,
    onOpenFolder: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onExportClick: () -> Unit,
) {
    val state = workspace.active
    // Every pixel of height up here is a log line less. Material pads buttons
    // to a 48 dp touch target, which a mouse-driven desktop app doesn't need.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            FileMenu(state, onOpenFiles, onOpenFolder, onSave, onSaveAs)

            var presetMenuOpen by remember { mutableStateOf(false) }
            val presetCount = state.selectedPresets.size
            OutlinedButton(
                onClick = { presetMenuOpen = true },
                enabled = state.index != null,
                modifier = CompactButton,
                contentPadding = CompactButtonPadding,
            ) {
                BarLabel(if (presetCount > 0) "Presets ($presetCount)" else "Presets")
            }
            // Presets combine, so the menu stays open across clicks — closing it
            // after each one would make selecting two of them needlessly fiddly.
            DropdownMenu(expanded = presetMenuOpen, onDismissRequest = { presetMenuOpen = false }) {
                for (preset in Preset.ALL) {
                    val applied = preset.name in state.selectedPresets
                    DropdownMenuItem(
                        text = {
                            Text(
                                preset.name,
                                // Weight as well as colour, so the applied state
                                // does not rest on colour alone.
                                fontWeight = if (applied) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (applied) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = { state.togglePreset(preset) },
                        modifier = if (applied) {
                            Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        } else Modifier,
                    )
                }
            }
            // The count is the cue that this view is filtered at all — easy to
            // miss on a tab whose filters came from Apply filters to all tabs.
            val filterCount = state.activeFilterCount
            TextButton(
                onClick = { state.clearFilters() },
                enabled = state.index != null,
                modifier = CompactButton,
                contentPadding = CompactButtonPadding,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (filterCount > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                BarLabel(
                    if (filterCount > 0) "Clear Filters ($filterCount)" else "Clear Filters",
                    bold = filterCount > 0,
                )
            }
            if (workspace.tabs.size > 1) {
                TooltipArea(
                    tooltip = {
                        TooltipText(
                            if (workspace.tabs.any { it.isLoading }) "Waiting for the other files to finish loading."
                            else "Copies this tab's filters and search to every open tab. The time range " +
                                "carries over only if you narrowed it; tags apply by name.",
                        )
                    },
                ) {
                    TextButton(
                        onClick = {
                            val n = workspace.applyFiltersToAll()
                            state.showStatus("Filters applied to %d other tab%s.".format(n, if (n == 1) "" else "s"))
                        },
                        enabled = workspace.canApplyFiltersToAll,
                        modifier = CompactButton,
                        contentPadding = CompactButtonPadding,
                    ) { BarLabel("Apply filters to all tabs") }
                }
            }

            Spacer(Modifier.weight(1f))
            Text(
                state.index?.file?.name ?: "No file loaded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TooltipArea(tooltip = { TooltipText("Export Filtered — save a copy of the lines in view (Ctrl+E)") }) {
                IconButton(onClick = onExportClick, enabled = state.canWriteView, modifier = Modifier.size(28.dp)) {
                    ExportIcon()
                }
            }
        }
    }
}

/**
 * One menu for everything about files, Sublime-style. Opening adds tabs, so
 * it never has to wait for this one to be idle; Save and Save As write this
 * tab's view, so they do.
 */
@Composable
private fun FileMenu(
    state: AppState,
    onOpenFiles: () -> Unit,
    onOpenFolder: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { open = true }, modifier = CompactButton, contentPadding = CompactButtonPadding) {
            BarLabel("File ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Closed before acting: the actions open native dialogs.
            FileMenuItem("Open File…", "Ctrl+O") { open = false; onOpenFiles() }
            FileMenuItem("Open Folder…", "Ctrl+Shift+O") { open = false; onOpenFolder() }
            HorizontalDivider()
            FileMenuItem("Save", "Ctrl+S", enabled = state.canSave) { open = false; onSave() }
            FileMenuItem("Save As…", "Ctrl+Shift+S", enabled = state.canWriteView) { open = false; onSaveAs() }
        }
    }
}

@Composable
private fun FileMenuItem(label: String, shortcut: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 13.sp) },
        trailingIcon = {
            Text(shortcut, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * A disk with an arrow out of it: Export saves a *copy*. A plain disk would
 * read as Save, which is a different thing (it rewrites this file).
 */
@Composable
private fun ExportIcon() {
    Box(Modifier.size(20.dp)) {
        Icon(
            Icons.Outlined.Save,
            contentDescription = "Export Filtered",
            modifier = Modifier.size(17.dp).align(Alignment.BottomStart),
        )
        Icon(
            Icons.Outlined.ArrowOutward,
            contentDescription = null,
            modifier = Modifier.size(10.dp).align(Alignment.TopEnd),
        )
    }
}

/**
 * Save keeps only the lines in view, so the hidden ones leave the file for
 * good. Says how many, and offers Save As to keep the original intact.
 */
@Composable
private fun SaveConfirmation(state: AppState, onSave: () -> Unit, onSaveAs: () -> Unit, onCancel: () -> Unit) {
    val hidden = state.hiddenLineCount
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Save ${state.file?.name.orEmpty()}?") },
        text = {
            Text(
                "Only the %,d lines in view are kept. The %,d hidden line%s will be removed from the file for good."
                    .format(state.filteredLines.size, hidden, if (hidden == 1) "" else "s"),
            )
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onSaveAs) { Text("Save As…") }
            }
        },
    )
}

/**
 * Closing (or quitting) with unsaved changes. Save here is the File menu's
 * Save, so when the filters hide lines it removes those too — and says so,
 * since that dialog is skipped.
 */
@Composable
private fun UnsavedChangesDialog(tab: AppState, onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    val hidden = tab.hiddenLineCount
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Save changes to ${tab.file?.name.orEmpty()}?") },
        text = {
            Text(
                buildString {
                    append("Your changes are lost if you don't save them.")
                    if (hidden > 0) {
                        append(" Saving also removes the %,d line%s the filters hide.".format(hidden, if (hidden == 1) "" else "s"))
                    }
                },
            )
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onDiscard) { Text("Don't Save") }
            }
        },
    )
}

private val CompactButton = Modifier.height(28.dp)
private val CompactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)

@Composable
private fun BarLabel(text: String, bold: Boolean = false) {
    Text(text, fontSize = 12.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
}

@Composable
private fun SearchBar(state: AppState, searchFocus: FocusRequester, onFocusChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        val finding = state.searchMode == SearchMode.FIND
        OutlinedTextField(
            value = state.searchText,
            onValueChange = { state.searchText = it; state.onSearchChanged() },
            placeholder = {
                Text(
                    if (finding) "Find in view (highlights, case insensitive)…"
                    else "Filter to matching lines (case insensitive)…",
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = { SearchModeToggle(state) },
            modifier = Modifier
                .weight(1f)
                .focusRequester(searchFocus)
                .onFocusChanged { onFocusChange(it.isFocused) },
        )
        Checkbox(
            checked = state.searchIsRegex,
            onCheckedChange = {
                state.searchIsRegex = it
                if (finding) state.onSearchChanged() else state.scheduleApply(0)
            },
        )
        Text("Regex", fontSize = 12.sp)
    }
}

/**
 * Flips the search box between narrowing the view (Filter) and highlighting
 * matches in place (Find), like the mode switch text editors have. A filter
 * icon with a slash through it in Find mode — tinted, so a non-default mode is
 * obvious — and a tooltip naming what a click will switch to.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SearchModeToggle(state: AppState) {
    val finding = state.searchMode == SearchMode.FIND
    TooltipArea(
        tooltip = {
            Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(4.dp)) {
                Text(
                    if (finding) "Find: highlights matches in place. Click to filter instead."
                    else "Filter: keeps only matching lines. Click to find in place.",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
    ) {
        IconButton(onClick = { state.toggleSearchMode() }) {
            Icon(
                imageVector = if (finding) Icons.Filled.FilterAltOff else Icons.Filled.FilterAlt,
                contentDescription = if (finding) "Find mode" else "Filter mode",
                tint = if (finding) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StatusBar(
    state: AppState,
    prefs: UiPrefs,
    onPrevMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onClearMatch: () -> Unit,
    onCopyLines: () -> Unit,
) {
    // Three parts: status on the left, the file's stats centred, occurrence
    // navigation on the right. Left and right take equal weights, so the
    // middle stays centred whatever either side shows.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                val progress = state.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(240.dp))
                    Text(state.progressLabel, fontSize = 11.sp, maxLines = 1)
                    TextButton(
                        onClick = { state.cancelWork() },
                        modifier = Modifier.height(22.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text("Cancel", fontSize = 11.sp) }
                } else {
                    Text(
                        state.statusMessage,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val notice = state.notice
                    if (notice != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            Text(
                                notice,
                                fontSize = 11.sp,
                                color = NoticeColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            NavArrow("✕", MaterialTheme.colorScheme.onSurfaceVariant) { state.dismissNotice() }
                        }
                    }
                }
            }

            // Total lines and the filtered count are already in "x / y lines" on the left.
            val index = state.index
            if (index != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    StatusStat("Errors", index.errorCount)
                    StatusStat("Warnings", index.warningCount)
                    StatusStat("Unique tags", index.tags.size)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                modifier = Modifier.weight(1f),
            ) {
                val lines = state.lineSelection
                if (lines != null) {
                    val n = lines.last - lines.first + 1
                    Text(
                        "%,d line%s selected".format(n, if (n == 1) "" else "s"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        NavArrow("Copy", MaterialTheme.colorScheme.primary, onCopyLines)
                        NavArrow("✕", MaterialTheme.colorScheme.onSurfaceVariant) { state.clearLineSelection() }
                    }
                }
                if (state.progress == null) {
                    val needle = state.highlightNeedle.let { if (it.length > 24) it.take(24) + "…" else it }
                    val highlightText = when {
                        state.highlightCounting -> "counting \"$needle\"…"
                        state.highlightCount != null -> "%,d lines contain \"%s\"".format(state.highlightCount, needle)
                        else -> null
                    }
                    if (highlightText != null && !state.highlightCounting) {
                        Text(highlightText, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if ((state.highlightCount ?: 0) > 0) {
                                if (state.currentMatchIndex >= 0) {
                                    Text(
                                        "${state.currentMatchIndex + 1} / ${state.highlightMatches.size}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                NavArrow("▲", MaterialTheme.colorScheme.primary, onPrevMatch)
                                NavArrow("▼", MaterialTheme.colorScheme.primary, onNextMatch)
                            }
                            NavArrow("✕", MaterialTheme.colorScheme.onSurfaceVariant, onClearMatch)
                        }
                    } else if (highlightText != null) {
                        Text(highlightText, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                // Only while zoomed, so it explains big or tiny text; a click resets.
                if (prefs.zoomPercent != 100) {
                    TooltipArea(tooltip = { TooltipText("Reset zoom (Ctrl+0)") }) {
                        Text(
                            "Zoom ${prefs.zoomPercent}%",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { prefs.resetZoom() }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Status-bar notices share the Warn line colour: worth noticing, not an error. */
private val NoticeColor = Color(0xFFFFB74D)

private val ZoomInKeys = setOf(Key.Equals, Key.Plus, Key.NumPadAdd)
private val ZoomOutKeys = setOf(Key.Minus, Key.NumPadSubtract)
private val ZoomResetKeys = setOf(Key.Zero, Key.NumPad0)

@Composable
private fun StatusStat(label: String, value: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("%,d".format(value), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Compact clickable glyph used for previous/next/clear selection controls. */
@Composable
internal fun NavArrow(glyph: String, color: Color, onClick: () -> Unit) {
    Text(
        glyph,
        fontSize = 13.sp,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
