package com.sherlog.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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

    fun onShortcut(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val exportEnabled = state.index != null && state.filteredLines.isNotEmpty() && !state.isBusy
        return when {
            // With whole lines selected, Ctrl+C is theirs; otherwise it falls
            // through to the focused text field's own copy.
            e.isCtrlPressed && e.key == Key.C && state.lineSelection != null -> {
                state.copySelectedLines(onCopyText); true
            }
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
            e.isCtrlPressed && e.key == Key.O -> { onOpenFiles(); true }
            e.isCtrlPressed && e.key == Key.E -> { if (exportEnabled) onExportClick(); exportEnabled }
            e.isCtrlPressed && e.key == Key.W -> { if (state.file != null) workspace.close(state); true }
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
            .onPreviewKeyEvent(::onShortcut)
            .focusTarget(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // The window's height is what keeps the results panel from starving
        // the viewer; captured here because Column shadows the receiver.
        BoxWithConstraints {
        val windowHeight = maxHeight
        Column {
            TabStrip(workspace, onAddFiles = onOpenFiles)
            // Keyed on the tab so UI-only state remembered below (open menus,
            // the tag list's scroll, the slider) belongs to one tab rather
            // than leaking into the next.
            key(state) {
                TopBar(workspace, onOpenFiles, onOpenFolder, onExportClick)
                HorizontalDivider()
                Row(Modifier.weight(1f)) {
                    FilterPanel(state, Modifier.width(300.dp).fillMaxSize())
                    VerticalDivider()
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
            // Opening adds tabs, so it never has to wait for this one to be idle.
            Button(onClick = onOpenFiles, modifier = CompactButton, contentPadding = CompactButtonPadding) {
                BarLabel("Open Log")
            }
            OutlinedButton(onClick = onOpenFolder, modifier = CompactButton, contentPadding = CompactButtonPadding) {
                BarLabel("Open Folder")
            }

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
            Button(
                onClick = onExportClick,
                enabled = state.index != null && state.filteredLines.isNotEmpty() && !state.isBusy,
                modifier = CompactButton,
                contentPadding = CompactButtonPadding,
            ) { BarLabel("Export Filtered") }
        }
    }
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

@Composable
private fun StatusBar(
    state: AppState,
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
                    Text(state.statusMessage, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            }
        }
    }
}

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
