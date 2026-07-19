package com.sherlog.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlog.filter.FilterMode
import com.sherlog.model.LogLevel
import com.sherlog.parser.LogcatLineParser

@Composable
fun FilterPanel(state: AppState, modifier: Modifier = Modifier) {
    val index = state.index
    Column(modifier.padding(8.dp)) {
        SectionTitle("Tags")
        OutlinedTextField(
            value = state.tagSearchText,
            onValueChange = { state.tagSearchText = it },
            placeholder = { Text("Search tags") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.sortTagsByCount = !state.sortTagsByCount }) {
                Text(if (state.sortTagsByCount) "Sorted by count" else "Sorted A–Z", fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            if (state.selectedTags.isNotEmpty()) {
                TextButton(onClick = { state.selectedTags = emptySet(); state.scheduleApply() }) {
                    Text("Clear (${state.selectedTags.size})", fontSize = 11.sp)
                }
            }
        }
        // How the checked tags apply. Flipping keeps the checked set, so
        // Show-only <-> Hide doubles as an "invert" gesture.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TagModeChip(state, FilterMode.SHOW_ONLY, "Show only")
            TagModeChip(state, FilterMode.HIDE, "Hide")
        }
        // Tag list — none selected means "show all tags". Both this and the
        // fields section below carry a weight: an unweighted fields Column
        // would be measured first and squeeze the tag list to zero height.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (index == null) {
                Text(
                    "No file loaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                val query = state.tagSearchText
                val byCount = state.sortTagsByCount
                val tagOrder = remember(index, query, byCount) {
                    val ids = (0 until index.tags.size).filter {
                        query.isBlank() || index.tags[it].contains(query, ignoreCase = true)
                    }
                    if (byCount) ids.sortedByDescending { index.tagCounts[it] }
                    else ids.sortedBy { index.tags[it].lowercase() }
                }
                if (tagOrder.isEmpty()) {
                    Text(
                        "No tags match \"$query\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                val listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 10.dp)) {
                    items(count = tagOrder.size, key = { tagOrder[it] }) { pos ->
                        val tagId = tagOrder[pos]
                        val tag = index.tags[tagId]
                        val checked = tag in state.selectedTags
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().height(28.dp).clickable {
                                state.selectedTags =
                                    if (checked) state.selectedTags - tag else state.selectedTags + tag
                                state.scheduleApply()
                            },
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(end = 4.dp))
                            Text(
                                tag,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "%,d".format(index.tagCounts[tagId]),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        val fieldScroll = rememberScrollState()
        Column(Modifier.weight(1.3f).verticalScroll(fieldScroll)) {
            SectionTitle("Levels")
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                for (level in listOf(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO, LogLevel.DEBUG, LogLevel.VERBOSE)) {
                    LevelCheckbox(state, level)
                }
            }
            LevelCheckboxRow(state, LogLevel.FATAL, "Fatal")
            LevelCheckboxRow(state, LogLevel.UNKNOWN, "Other (unparsed lines)")

            SectionTitle("PID")
            SmallField(
                state,
                state.pidText,
                { state.pidText = it },
                if (state.pidMode == FilterMode.HIDE) "Hide: 1913, 6432" else "Show only: 1913, 6432",
                trailing = { PidModeToggle(state) },
            )

            SectionTitle("Time range")
            SmallField(state, state.timeFromText, { state.timeFromText = it }, "From: [YYYY-]MM-DD HH:MM:SS")
            SmallField(state, state.timeToText, { state.timeToText = it }, "To: [YYYY-]MM-DD HH:MM:SS")
            TimeRangeSlider(state)

            SectionTitle("Exclude lines containing")
            SmallField(state, state.excludeText, { state.editExcludeText(it) }, "adbd, CCodec, Audio…")

            SectionTitle("Keep only lines containing")
            SmallField(state, state.includeText, { state.editIncludeText(it) }, "FATAL EXCEPTION, Caused by…")
        }
    }
}

@Composable
private fun TagModeChip(state: AppState, mode: FilterMode, label: String) {
    FilterChip(
        selected = state.tagMode == mode,
        onClick = {
            if (state.tagMode != mode) {
                state.tagMode = mode
                state.scheduleApply()
            }
        },
        label = { Text(label, fontSize = 11.sp) },
    )
}

/**
 * Two-thumb slider over the file's full time span. The From/To text fields
 * are the source of truth: dragging writes formatted timestamps into them
 * (re-filtering only when the thumb is released), and typing in the fields
 * moves the thumbs. Positions are normalized to 0..1 because raw millisecond
 * values exceed Float precision on multi-day logs.
 */
@Composable
private fun TimeRangeSlider(state: AppState) {
    val index = state.index ?: return
    val first = index.firstTimestampMs ?: return
    val last = index.lastTimestampMs ?: return
    if (last <= first) return
    val span = (last - first).toFloat()

    fun toFraction(text: String, default: Float): Float =
        LogcatLineParser.parseTimestamp(text)
            ?.let { ((it - first).toFloat() / span).coerceIn(0f, 1f) } ?: default

    val fromF = toFraction(state.timeFromText, 0f)
    val toF = toFraction(state.timeToText, 1f).coerceAtLeast(fromF)

    RangeSlider(
        value = fromF..toF,
        onValueChange = { range ->
            state.timeFromText = LogcatLineParser.formatTimestamp(first + (range.start * span).toLong())
            state.timeToText = LogcatLineParser.formatTimestamp(first + (range.endInclusive * span).toLong())
        },
        onValueChangeFinished = { state.scheduleApply(0) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun SmallField(
    state: AppState,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it); state.scheduleApply() },
        placeholder = { Text(placeholder, fontSize = 11.sp) },
        trailingIcon = trailing,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/**
 * Flips the PID field between keeping and dropping the listed PIDs. A word
 * rather than an icon: "show only" vs "hide" is a logic distinction, and every
 * icon for it (an eye most of all) reads as something else. Hide mode is
 * tinted, so a non-default mode can't be left on unnoticed.
 */
@Composable
private fun PidModeToggle(state: AppState) {
    val hiding = state.pidMode == FilterMode.HIDE
    TextButton(
        onClick = {
            state.pidMode = if (hiding) FilterMode.SHOW_ONLY else FilterMode.HIDE
            state.scheduleApply(0)
        },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Text(
            if (hiding) "hide ▾" else "only ▾",
            fontSize = 11.sp,
            color = if (hiding) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LevelCheckbox(state: AppState, level: LogLevel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Checkbox(
            checked = level in state.enabledLevels,
            onCheckedChange = { on ->
                state.enabledLevels = if (on) state.enabledLevels + level else state.enabledLevels - level
                state.scheduleApply()
            },
        )
        Text(level.letter.toString(), fontSize = 11.sp)
    }
}

@Composable
private fun LevelCheckboxRow(state: AppState, level: LogLevel, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = level in state.enabledLevels,
            onCheckedChange = { on ->
                state.enabledLevels = if (on) state.enabledLevels + level else state.enabledLevels - level
                state.scheduleApply()
            },
        )
        Text(label, fontSize = 11.sp)
    }
}
