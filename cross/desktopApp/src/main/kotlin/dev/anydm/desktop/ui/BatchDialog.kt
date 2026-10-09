package dev.anydm.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anydm.desktop.chrome.PushButton
import dev.anydm.desktop.theme.LocalTokens
import dev.anydm.model.BatchItem
import dev.anydm.model.BatchKind
import dev.anydm.model.BatchOutcome
import dev.anydm.store.BatchState
import dev.anydm.store.PREVIEW_SHOWN
import dev.anydm.store.batchSummary
import dev.anydm.store.linkCount
import dev.anydm.store.previewMore

private val OUTCOME_LABEL =
    mapOf(
        BatchOutcome.ADDED to "Added",
        BatchOutcome.DUPLICATE to "In your list",
        BatchOutcome.ERROR to "Failed",
    )

/**
 * Add many links at once: a list or a pattern such as `img[001-120].png`, what it names as typing
 * pauses, then every link's answer. A sheet in [dev.anydm.desktop.chrome.NativeDialog]'s style, wider for
 * the list; Esc closes it. [presetLabel] is the toolbar's quality, which a batch uses too.
 */
@Composable
fun BatchDialog(
    state: BatchState,
    presetLabel: String,
    onKind: (BatchKind) -> Unit,
    onText: (String) -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onStartOver: () -> Unit,
    onClose: () -> Unit,
) {
    val t = LocalTokens.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = t.bar,
            border = BorderStroke(1.dp, t.separator),
            shadowElevation = 16.dp,
            modifier =
                Modifier
                    .width(520.dp)
                    .testTag("batch")
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onClose()
                            true
                        } else {
                            false
                        }
                    },
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add many links", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                val results = state.results
                if (results != null) {
                    Results(results, onOpen)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        PushButton("Add more", onClick = onStartOver)
                        PushButton("Done", primary = true, onClick = onClose)
                    }
                } else {
                    Form(state, presetLabel, onKind, onText)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        PushButton("Cancel", enabled = !state.adding, onClick = onClose)
                        val count = state.preview?.count ?: 0
                        val label =
                            when {
                                state.adding -> "Adding…"
                                count > 0 -> "Add ${linkCount(count)}"
                                else -> "Add"
                            }
                        PushButton(label, primary = true, enabled = state.canAdd, onClick = onAdd)
                    }
                }
            }
        }
    }
}

@Composable
private fun Form(
    state: BatchState,
    presetLabel: String,
    onKind: (BatchKind) -> Unit,
    onText: (String) -> Unit,
) {
    val t = LocalTokens.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PushButton("List", primary = state.kind == BatchKind.LIST) { onKind(BatchKind.LIST) }
        PushButton("Pattern", primary = state.kind == BatchKind.PATTERN) { onKind(BatchKind.PATTERN) }
    }
    val pattern = state.kind == BatchKind.PATTERN
    Box(
        Modifier
            .fillMaxWidth()
            .let { if (pattern) it.height(30.dp) else it.height(140.dp) }
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, t.separator, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (state.text.isEmpty()) {
            Text(
                if (pattern) "https://example.com/img[001-120].png" else "One link per line",
                color = t.secondaryText,
                fontSize = 13.sp,
            )
        }
        BasicTextField(
            state.text,
            onText,
            singleLine = pattern,
            textStyle = TextStyle(fontSize = 13.sp, color = t.text),
            cursorBrush = SolidColor(t.accent),
            modifier = Modifier.fillMaxSize().testTag(if (pattern) "batch-pattern" else "batch-list"),
        )
    }
    if (pattern) {
        Text("Ranges like [01-50], [1-9] or [a-z] expand; a leading zero keeps the padding.", color = t.secondaryText, fontSize = 12.sp)
    }
    Preview(state)
    Text("Quality for links on a site: $presetLabel, from the toolbar", color = t.secondaryText, fontSize = 12.sp)
    state.addError?.let { Text(it, color = t.error) }
}

@Composable
private fun Preview(state: BatchState) {
    val t = LocalTokens.current
    val preview = state.preview
    Column(Modifier.heightIn(min = 40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            state.previewError != null -> {
                Text(state.previewError!!, color = t.error)
            }

            state.checking -> {
                Text("Checking…", color = t.secondaryText)
            }

            preview != null -> {
                Text(linkCount(preview.count), color = t.text, fontWeight = FontWeight.SemiBold)
                preview.urls.take(PREVIEW_SHOWN).forEach { url ->
                    Text(url, color = t.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                previewMore(preview.count)?.let { Text(it, color = t.secondaryText, fontSize = 12.sp) }
            }

            else -> {
                Text("Paste links or type a pattern to see what will be added.", color = t.secondaryText)
            }
        }
    }
}

@Composable
private fun Results(
    results: List<BatchItem>,
    onOpen: (String) -> Unit,
) {
    val t = LocalTokens.current
    Text(batchSummary(results), color = t.text, fontWeight = FontWeight.SemiBold)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        itemsIndexed(results) { _, item ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val tint =
                    when (item.outcome) {
                        BatchOutcome.ADDED -> t.ok
                        BatchOutcome.DUPLICATE -> t.secondaryText
                        BatchOutcome.ERROR -> t.error
                    }
                Text(OUTCOME_LABEL.getValue(item.outcome), color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f)) {
                    Text(item.url, color = t.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.outcome == BatchOutcome.ERROR && item.message.isNotEmpty()) {
                        Text(item.message, color = t.error, fontSize = 11.sp)
                    }
                }
                item.downloadId?.let { id ->
                    Text(
                        "Open",
                        Modifier.clip(RoundedCornerShape(5.dp)).clickable { onOpen(id) }.padding(horizontal = 6.dp, vertical = 3.dp),
                        color = t.accent,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
