package com.sherlog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlog.filter.Preset

@Composable
fun App(state: AppState, onOpenClick: () -> Unit, onExportClick: () -> Unit) {
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
                        state.filteredLines.isEmpty() ->
                            EmptyViewer("No lines match the current filters.", Modifier.weight(1f).fillMaxWidth())
                        else -> LogViewer(
                            index = index,
                            provider = provider,
                            filteredLines = state.filteredLines,
                            searchQuery = state.appliedFilter.searchQuery,
                            searchIsRegex = state.appliedFilter.searchIsRegex,
                            selectionHighlight = state.selectionHighlight,
                            onSelectionChange = { state.onViewerSelection(it) },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }
            HorizontalDivider()
            StatusBar(state)
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
        OutlinedButton(onClick = { presetMenuOpen = true }, enabled = state.index != null) {
            Text("Presets")
        }
        DropdownMenu(expanded = presetMenuOpen, onDismissRequest = { presetMenuOpen = false }) {
            for (preset in Preset.ALL) {
                DropdownMenuItem(
                    text = { Text(preset.name) },
                    onClick = { presetMenuOpen = false; state.applyPreset(preset) },
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
private fun StatusBar(state: AppState) {
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
            val needle = state.selectionHighlight.let { if (it.length > 24) it.take(24) + "…" else it }
            val highlightPart = when {
                state.highlightCounting -> " · counting \"$needle\"…"
                state.highlightCount != null -> " · %,d lines contain \"%s\"".format(state.highlightCount, needle)
                else -> ""
            }
            Text(state.statusMessage + highlightPart, fontSize = 11.sp)
        }
    }
}
