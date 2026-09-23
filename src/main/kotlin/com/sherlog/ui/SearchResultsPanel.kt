package com.sherlog.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlog.filter.FilterEngine

/**
 * Results of a cross-tab search (Ctrl+Shift+F), grouped by file. Clicking a
 * result shows that line in its own tab.
 *
 * All of its state lives in [TabSearch] rather than here: `App` re-creates this
 * composable under its `key(state)` on every tab switch, and clicking a result
 * *is* a tab switch — anything remembered here (the scroll position above all)
 * would reset under the user's pointer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchResultsPanel(
    workspace: Workspace,
    queryFocus: FocusRequester,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val search = workspace.search
    val matcher = remember(search.resultsQuery, search.resultsAreRegex) {
        if (search.resultsQuery.isBlank()) null
        else FilterEngine.SearchMatcher(search.resultsQuery, search.resultsAreRegex)
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f))) {
            Header(workspace, queryFocus, onClose)
            HorizontalLine()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = search.listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
                    for (group in search.results) {
                        item(key = "group-${group.name}-${group.tab.hashCode()}") {
                            GroupHeader(group, collapsed = group.tab in search.collapsed) {
                                search.toggleGroup(group.tab)
                            }
                        }
                        if (group.tab in search.collapsed) continue
                        items(group.previews.size, key = { "${group.tab.hashCode()}-${group.previews[it].line}" }) { i ->
                            val hit = group.previews[i]
                            ResultRow(hit, matcher) { workspace.openHit(group.tab, hit) }
                        }
                        if (group.truncated) {
                            item(key = "more-${group.tab.hashCode()}") {
                                Text(
                                    "     … %,d more in %s — narrow the filters or the query".format(
                                        group.total - group.previews.size,
                                        group.name,
                                    ),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                    if (search.results.isEmpty() && !search.isSearching) {
                        item {
                            Text(
                                when {
                                    search.invalidPattern -> "That regex doesn't compile."
                                    search.resultsQuery.isEmpty() -> "Type something and press Enter to search every open tab."
                                    else -> "No matches in any open tab."
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(search.listState),
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header(workspace: Workspace, queryFocus: FocusRequester, onClose: () -> Unit) {
    val search = workspace.search
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        TooltipArea(tooltip = { TooltipText("Searches each tab's current filtered view. Enter searches again.") }) {
            OutlinedTextField(
                value = search.query,
                onValueChange = { search.query = it },
                placeholder = { Text("Search all tabs…", fontSize = 12.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .width(320.dp)
                    .focusRequester(queryFocus)
                    .onPreviewKeyEvent { e ->
                        when {
                            e.type != KeyEventType.KeyDown -> false
                            e.key == Key.Enter -> { workspace.searchAllTabs(); true }
                            e.key == Key.Escape -> { onClose(); true }
                            else -> false
                        }
                    },
            )
        }
        Checkbox(checked = search.isRegex, onCheckedChange = { search.isRegex = it })
        Text("Regex", fontSize = 12.sp)

        if (search.isSearching) {
            LinearProgressIndicator(Modifier.width(120.dp))
            Text("Searching ${search.progressLabel}…", fontSize = 11.sp, maxLines = 1)
            TextButton(
                onClick = { search.cancel() },
                modifier = Modifier.height(22.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) { Text("Cancel", fontSize = 11.sp) }
        } else {
            val files = search.results.count { it.total > 0 }
            Text(
                if (search.resultsQuery.isEmpty()) "" else "%,d matches in %d file%s".format(
                    search.totalMatches, files, if (files == 1) "" else "s",
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.weight(1f))
        NavArrow("✕", MaterialTheme.colorScheme.onSurfaceVariant, onClose)
    }
}

@Composable
private fun GroupHeader(group: TabSearch.FileResult, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            if (collapsed) "▸" else "▾",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            "%s (%,d)".format(group.name, group.total),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ResultRow(hit: TabSearch.Hit, matcher: FilterEngine.SearchMatcher?, onClick: () -> Unit) {
    // The line number is part of the same text node so a result is uniquely
    // addressable — the log viewer shows the very same line text.
    val label = remember(hit, matcher) { highlighted("%,8d  %s".format(hit.line + 1, hit.text), matcher) }
    Text(
        label,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 1.dp),
    )
}

/** Paints the query inside a preview with the viewer's own search colour. */
private fun highlighted(text: String, matcher: FilterEngine.SearchMatcher?): AnnotatedString {
    val regex = matcher?.regex ?: return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (m in regex.findAll(text)) {
            if (!m.range.isEmpty()) addStyle(searchHighlightStyle, m.range.first, m.range.last + 1)
        }
    }
}

@Composable
private fun HorizontalLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/**
 * The bar that resizes the panel. Dragging it up grows the panel; [App] clamps
 * the height against the window so the log view can't be squeezed to nothing.
 */
@Composable
fun PanelResizeHandle(search: TabSearch, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val state = rememberDraggableState { delta -> search.height -= with(density) { delta.toDp() } }
    Box(
        modifier
            .fillMaxWidth()
            .height(5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            .draggable(state, Orientation.Vertical),
    )
}
