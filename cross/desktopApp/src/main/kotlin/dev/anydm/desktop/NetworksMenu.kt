package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val NetworksMenuWidth = 400.dp

@Composable
fun NetworksMenu(
    networks: List<Network>,
    onNetworksChange: (List<Network>) -> Unit,
    unit: SpeedUnit,
    onDismiss: () -> Unit,
    onOpenSettings: (SettingsSection, String?) -> Unit,
    modifier: Modifier = Modifier,
    onEditingChange: (Boolean) -> Unit = {},
    maxHeight: Dp = Dp.Unspecified,
    keychainNotes: Map<String, String> = emptyMap(),
) {
    val c = colors
    var pageId by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var rejected by remember { mutableStateOf<String?>(null) }
    var proxyEditing by remember { mutableStateOf(false) }
    var proxyFocused by remember { mutableStateOf(false) }

    // A focused text input anywhere in the menu (rename or proxy field) keeps Home's paste and Esc shortcuts out of its way.
    LaunchedEffect(editingId, proxyFocused) { onEditingChange(editingId != null || proxyFocused) }
    DisposableEffect(Unit) { onDispose { onEditingChange(false) } }

    fun showPage(id: String?) {
        pageId = id
        proxyEditing = false
    }

    fun cancelEdit() {
        editingId = null
        rejected = null
    }

    /** Ends the current edit as a save attempt; returns the list to continue from. */
    fun settle(): List<Network> {
        val id = editingId ?: return networks
        val result = rename(networks, id, draft.text)
        cancelEdit()
        return if (result is RenameResult.Ok) result.networks else networks
    }

    fun commit() {
        val id = editingId ?: return
        when (val result = rename(networks, id, draft.text)) {
            is RenameResult.Ok -> {
                cancelEdit()
                onNetworksChange(result.networks)
            }

            is RenameResult.Rejected -> {
                rejected = result.reason
            }
        }
    }

    val shape = RoundedCornerShape(Radius)
    val focusManager = LocalFocusManager.current
    val menuFocus = remember { FocusRequester() }
    LaunchedEffect(editingId, pageId, proxyEditing) { if (editingId == null) menuFocus.requestFocus() }
    Column(
        modifier
            .testTag("networks-menu")
            .focusRequester(menuFocus)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && editingId == null) {
                    if (pageId != null) showPage(null) else onDismiss()
                    true
                } else {
                    false
                }
            }.focusable()
            .width(NetworksMenuWidth)
            .heightIn(max = maxHeight)
            .background(c.surface, shape)
            .border(1.dp, c.border, shape)
            .pointerInput(Unit) { detectTapGestures { if (editingId != null) focusManager.clearFocus() } },
    ) {
        val page = pageId?.let { id -> networks.firstOrNull { it.id == id } }
        if (page != null) {
            NetworkSettingsPage(
                page,
                editing = editingId == page.id,
                draft = draft,
                error = if (editingId == page.id) rejected else null,
                onDraft = {
                    draft = it
                    rejected = null
                },
                onStartRename = {
                    val base = settle()
                    if (base !== networks) onNetworksChange(base)
                    val name = base.first { it.id == page.id }.name
                    draft = TextFieldValue(name, TextRange(0, name.length))
                    rejected = null
                    editingId = page.id
                },
                onCommit = ::commit,
                onCancel = ::cancelEdit,
                onColor = { onNetworksChange(setColor(settle(), page.id, it)) },
                onProxySave = { onNetworksChange(setProxy(settle(), page.id, it)) },
                onBack = {
                    val base = settle()
                    if (base !== networks) onNetworksChange(base)
                    showPage(null)
                },
                onOpenLimits = { onOpenSettings(SettingsSection.SpeedData, page.id) },
                proxyEditing = proxyEditing,
                onProxyEditing = { proxyEditing = it },
                onTextFocus = { proxyFocused = it },
                modifier = Modifier.weight(1f, fill = false),
                keychainNote = keychainNotes[page.id],
            )
            return@Column
        }
        Text(
            "Networks",
            color = c.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
        MenuDivider()
        val lastEnabled = enabledCount(networks) <= 1
        networks.forEach { n ->
            NetworkRow(
                n,
                unit,
                canToggle = !(n.enabled && lastEnabled),
                editing = editingId == n.id,
                draft = draft,
                error = if (editingId == n.id) rejected else null,
                onDraft = {
                    draft = it
                    rejected = null
                },
                onToggle = { onNetworksChange(toggle(settle(), n.id)) },
                onStartRename = {
                    val base = settle()
                    if (base !== networks) onNetworksChange(base)
                    val name = base.first { it.id == n.id }.name
                    draft = TextFieldValue(name, TextRange(0, name.length))
                    rejected = null
                    editingId = n.id
                },
                onCommit = ::commit,
                onCancel = ::cancelEdit,
                onOpenPage = {
                    val base = settle()
                    if (base !== networks) onNetworksChange(base)
                    showPage(n.id)
                },
            )
        }
        MenuDivider()
        Box(
            Modifier
                .testTag("networks-limits-link")
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = { onOpenSettings(SettingsSection.SpeedData, null) })
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Speed & data limits", color = c.accent, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun MenuDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun NetworkRow(
    n: Network,
    unit: SpeedUnit,
    canToggle: Boolean,
    editing: Boolean,
    draft: TextFieldValue,
    error: String?,
    onDraft: (TextFieldValue) -> Unit,
    onToggle: () -> Unit,
    onStartRename: () -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    onOpenPage: () -> Unit,
) {
    val c = colors
    val dot =
        if (!n.enabled) {
            c.textSecondary.copy(alpha = 0.45f)
        } else {
            networkColor(n.kind, n.color, c)
        }
    Column(Modifier.testTag("network-row-${n.id}").fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .testTag("network-dot-${n.id}")
                    .size(28.dp)
                    .clickable(enabled = canToggle, role = Role.Button, onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(8.dp).networkDot("network-color-${n.id}", n.color).background(dot, CircleShape)) }
            Row(
                Modifier.weight(1f).padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (editing) {
                    RenameField(draft, error, onDraft, onCommit, onCancel, Modifier.weight(1f))
                } else {
                    Text(
                        n.name,
                        color = if (n.enabled) c.text else c.textSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).testTag("network-name-${n.id}"),
                    )
                    Box(
                        Modifier.testTag("network-rename-${n.id}").size(24.dp).clickable(role = Role.Button, onClick = onStartRename),
                        contentAlignment = Alignment.Center,
                    ) { PencilIcon(c.textSecondary) }
                }
                KindTag(n.kind.label)
            }
            Text(
                when {
                    !n.enabled -> "Off"
                    n.bytesPerSec <= 0 -> "Idle"
                    else -> formatSpeed(n.bytesPerSec, unit)
                },
                color = c.textSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp).testTag("network-speed-${n.id}"),
            )
            Box(
                Modifier.testTag("network-chevron-${n.id}").size(28.dp).clickable(role = Role.Button, onClick = onOpenPage),
                contentAlignment = Alignment.Center,
            ) { ChevronIcon(c.textSecondary) }
        }
    }
}

@Composable
internal fun RenameField(
    draft: TextFieldValue,
    error: String?,
    onDraft: (TextFieldValue) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    val c = colors
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        draft,
        onDraft,
        singleLine = true,
        textStyle = TextStyle(color = c.text, fontSize = 14.sp),
        cursorBrush = SolidColor(c.text),
        modifier =
            modifier
                .height(28.dp)
                .border(1.dp, if (error != null) c.danger else c.accent, shape)
                .padding(horizontal = 8.dp)
                .focusRequester(focus)
                .onFocusChanged {
                    if (hadFocus && !it.isFocused) onCommit()
                    hadFocus = it.isFocused
                }.onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) {
                        false
                    } else if (it.key == Key.Enter || it.key == Key.NumPadEnter) {
                        onCommit()
                        true
                    } else if (it.key == Key.Escape) {
                        onCancel()
                        true
                    } else {
                        false
                    }
                }.semantics { if (error != null) error(error) }
                .testTag("network-rename-field"),
        decorationBox = { inner -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) { inner() } },
    )
}

@Composable
private fun KindTag(label: String) {
    val c = colors
    Text(
        label,
        color = c.textSecondary,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier.background(c.track, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun PencilIcon(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val st = 1.3f * density
        drawLine(color, Offset(w * .2f, w * .8f), Offset(w * .78f, w * .22f), st, StrokeCap.Round)
        drawLine(color, Offset(w * .2f, w * .8f), Offset(w * .2f, w * .62f), st, StrokeCap.Round)
        drawLine(color, Offset(w * .2f, w * .8f), Offset(w * .38f, w * .8f), st, StrokeCap.Round)
        drawLine(color, Offset(w * .66f, w * .34f), Offset(w * .78f, w * .22f), st * 2.2f, StrokeCap.Round)
    }
}
