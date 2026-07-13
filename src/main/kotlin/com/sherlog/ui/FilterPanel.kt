package com.sherlog.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlog.model.LogLevel

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
            SmallField(state, state.pidText, { state.pidText = it }, "e.g. 1913, 6432")

            SectionTitle("Time range")
            SmallField(state, state.timeFromText, { state.timeFromText = it }, "From: MM-DD HH:MM:SS")
            SmallField(state, state.timeToText, { state.timeToText = it }, "To: MM-DD HH:MM:SS")

            SectionTitle("Exclude lines containing")
            SmallField(state, state.excludeText, { state.excludeText = it }, "adbd, CCodec, Audio…")

            SectionTitle("Keep only lines containing")
            SmallField(state, state.includeText, { state.includeText = it }, "FATAL EXCEPTION, Caused by…")
        }
    }
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
private fun SmallField(state: AppState, value: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it); state.scheduleApply() },
        placeholder = { Text(placeholder, fontSize = 11.sp) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
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
