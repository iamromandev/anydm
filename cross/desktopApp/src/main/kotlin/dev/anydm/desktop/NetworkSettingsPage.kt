package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One network's settings inside the Networks menu panel: name, colour, proxy and the limits link. */
@Composable
internal fun NetworkSettingsPage(
    n: Network,
    editing: Boolean,
    draft: TextFieldValue,
    error: String?,
    onDraft: (TextFieldValue) -> Unit,
    onStartRename: () -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    onColor: (NetworkColor) -> Unit,
    onProxySave: (Proxy) -> Unit,
    onBack: () -> Unit,
    onOpenLimits: () -> Unit,
    proxyEditing: Boolean,
    onProxyEditing: (Boolean) -> Unit,
    onTextFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = colors
    Column(modifier.testTag("network-page")) {
        Row(
            Modifier
                .testTag("network-page-back")
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back to networks" }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("‹", color = c.textSecondary, fontSize = 16.sp, maxLines = 1)
            Text(
                n.name,
                color = c.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("network-page-title"),
            )
        }
        MenuDivider()
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Section("NAME") {
                if (editing) {
                    RenameField(draft, error, onDraft, onCommit, onCancel, Modifier.fillMaxWidth())
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            n.name,
                            color = c.text,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).testTag("network-page-name"),
                        )
                        Box(
                            Modifier.testTag("network-page-rename").size(24.dp).clickable(role = Role.Button, onClick = onStartRename),
                            contentAlignment = Alignment.Center,
                        ) { PencilIcon(c.textSecondary) }
                    }
                }
            }
            Section("COLOR") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    NetworkColor.entries.forEach { k -> Swatch(k, k == n.color) { onColor(k) } }
                    if (n.color == null) {
                        Text(
                            "Default",
                            color = c.textSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp).testTag("network-page-color-default"),
                        )
                    }
                }
            }
            key(n.id) {
                Section("PROXY") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            proxySummary(n.proxy),
                            color = c.textSecondary,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).testTag("network-page-proxy-summary"),
                        )
                        if (!proxyEditing) OutlineButton("Edit", "network-page-proxy-edit", { onProxyEditing(true) })
                    }
                    if (proxyEditing) {
                        ProxyEditor(
                            n.proxy,
                            onSave = {
                                onProxySave(it)
                                onProxyEditing(false)
                            },
                            onCancel = { onProxyEditing(false) },
                            tagPrefix = "page",
                            onTextFocus = onTextFocus,
                        )
                    }
                }
            }
            MenuDivider()
            Box(
                Modifier
                    .testTag("network-page-limits")
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpenLimits)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Speed & data limits for ${n.name}",
                    color = c.accent,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = colors.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp, maxLines = 1)
        content()
    }
}

@Composable
private fun Swatch(
    color: NetworkColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = colors
    Box(
        Modifier
            .testTag("swatch-${color.name.lowercase()}")
            .size(28.dp)
            .border(2.dp, if (selected) c.text else Color.Transparent, CircleShape)
            .padding(4.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = color.label }
            .background(swatch(color, c), CircleShape),
    )
}
