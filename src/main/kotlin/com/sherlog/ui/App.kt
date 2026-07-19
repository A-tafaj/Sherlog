package com.sherlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlog.filter.Preset
import kotlinx.coroutines.launch

@Composable
fun App(state: AppState, onOpenClick: () -> Unit, onExportClick: () -> Unit) {
    // Hoisted here so the status-bar next/prev buttons can scroll the viewer.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            TopBar(state, onOpenClick, onExportClick)
            HorizontalDivider()
            DashboardBar(state)
            HorizontalDivider()
            Row(Modifier.weight(1f)) {
                FilterPanel(state, Modifier.width(300.dp).fillMaxSize())
                VerticalDivider()
                Column(Modifier.weight(1f)) {
                    SearchBar(state)
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
                            selectionHighlight = state.selectionHighlight,
                            currentMatchPosition = state.currentMatchPosition,
                            onSelectionChange = { state.onViewerSelection(it) },
                            listState = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }
            HorizontalDivider()
            StatusBar(
                state,
                onPrevMatch = { state.prevMatch()?.let { pos -> scope.launch { listState.animateScrollToItem(pos) } } },
                onNextMatch = { state.nextMatch()?.let { pos -> scope.launch { listState.animateScrollToItem(pos) } } },
                onClearMatch = { state.clearHighlight() },
            )
        }
    }
}

@Composable
private fun TopBar(state: AppState, onOpenClick: () -> Unit, onExportClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Button(onClick = onOpenClick, enabled = !state.isBusy) { Text("Open Log") }

        var presetMenuOpen by remember { mutableStateOf(false) }
        val presetCount = state.selectedPresets.size
        OutlinedButton(onClick = { presetMenuOpen = true }, enabled = state.index != null) {
            Text(if (presetCount > 0) "Presets ($presetCount)" else "Presets")
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
        TextButton(onClick = { state.clearFilters() }, enabled = state.index != null) { Text("Clear Filters") }

        Spacer(Modifier.weight(1f))
        Text(
            state.index?.file?.name ?: "No file loaded",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onExportClick,
            enabled = state.index != null && state.filteredLines.isNotEmpty() && !state.isBusy,
        ) { Text("Export Filtered") }
    }
}

@Composable
private fun DashboardBar(state: AppState) {
    val index = state.index
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Stat("Total lines", index?.lineCount)
        Stat("Errors", index?.errorCount)
        Stat("Warnings", index?.warningCount)
        Stat("Unique tags", index?.tags?.size)
        Stat("Filtered", if (index == null) null else state.filteredLines.size)
    }
}

@Composable
private fun Stat(label: String, value: Int?) {
    Column {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value?.let { "%,d".format(it) } ?: "—",
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun SearchBar(state: AppState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = state.searchText,
            onValueChange = { state.searchText = it; state.scheduleApply(500) },
            placeholder = { Text("Search (case insensitive)…") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = state.searchIsRegex,
            onCheckedChange = { state.searchIsRegex = it; state.scheduleApply(0) },
        )
        Text("Regex", fontSize = 12.sp)
    }
}

@Composable
private fun StatusBar(
    state: AppState,
    onPrevMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onClearMatch: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        val progress = state.progress
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(240.dp))
            Text(state.progressLabel, fontSize = 11.sp)
            TextButton(onClick = { state.cancelWork() }) { Text("Cancel", fontSize = 11.sp) }
        } else {
            Text(state.statusMessage, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            val needle = state.selectionHighlight.let { if (it.length > 24) it.take(24) + "…" else it }
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

/** Compact clickable glyph used for previous/next/clear selection controls. */
@Composable
private fun NavArrow(glyph: String, color: Color, onClick: () -> Unit) {
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
