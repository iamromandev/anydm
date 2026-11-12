package dev.anydm.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val HIDDEN_PASSWORD = "Password, hidden"
private const val REQUIRED = "Required"

/**
 * Edits a [Proxy] as a draft: nothing leaves until Save. The caller keys it on the network id so a draft is never
 * shared between networks. [tagPrefix] is `page` or `chip-<id>`.
 */
@Composable
internal fun ProxyEditor(
    initial: Proxy,
    onSave: (Proxy) -> Unit,
    onCancel: () -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    onTextFocus: (Boolean) -> Unit = {},
) {
    val c = colors
    val start = remember { draftOf(initial) }
    var type by remember { mutableStateOf(start.type) }
    var host by remember { mutableStateOf(start.host) }
    var port by remember { mutableStateOf(start.port) }
    var user by remember { mutableStateOf(start.user) }
    var password by remember { mutableStateOf(start.password) }
    var reveal by remember { mutableStateOf(false) }
    var touchedHost by remember { mutableStateOf(false) }
    var touchedPort by remember { mutableStateOf(false) }
    val draft = ProxyDraft(type, host, port, user, password)
    val errors = proxyErrors(draft)

    fun tag(name: String) = "proxy-$tagPrefix-$name"

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProxyType.entries.forEach { t ->
                RadioDot(type == t, { type = t }, tag("type-${t.name.lowercase()}"), t.label)
            }
        }
        if (type != ProxyType.None) {
            val hostShown = host.isNotEmpty() || touchedHost
            val portShown = port.isNotEmpty() || touchedPort
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProxyField(
                        tag("host"),
                        "Host",
                        host,
                        {
                            host = it
                            touchedHost = true
                        },
                        "10.0.0.2",
                        hostShown && errors.host != null,
                        Modifier.weight(1f),
                        onTouch = { touchedHost = true },
                        onFocusChange = onTextFocus,
                    )
                    ProxyField(
                        tag("port"),
                        "Port",
                        port,
                        {
                            port = it
                            touchedPort = true
                        },
                        "8080",
                        portShown && errors.port != null,
                        Modifier.width(120.dp),
                        onTouch = { touchedPort = true },
                        onFocusChange = onTextFocus,
                    )
                }
                if (hostShown) ErrorText(tag("error-host"), if (host.isEmpty()) REQUIRED else errors.host)
                if (portShown) ErrorText(tag("error-port"), if (port.isEmpty()) REQUIRED else errors.port)
            }
            ProxyField(
                tag("user"),
                "Username",
                user,
                { user = it },
                "optional",
                false,
                Modifier.fillMaxWidth(),
                onFocusChange = onTextFocus,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProxyField(
                        tag("password"),
                        "Password",
                        password,
                        { password = it },
                        "optional",
                        errors.auth != null,
                        Modifier.weight(1f),
                        hidden = !reveal,
                        onFocusChange = onTextFocus,
                    )
                    IconBox(tag("reveal"), if (reveal) "Hide password" else "Show password", { reveal = !reveal }) {
                        EyeIcon(it, crossed = !reveal)
                    }
                }
                ErrorText(tag("error-auth"), errors.auth)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            OutlineButton("Cancel", tag("cancel"), onCancel)
            PrimaryButton("Save", tag("save"), !errors.any) { proxyOf(draft)?.let(onSave) }
        }
    }
}

@Composable
private fun ErrorText(
    tag: String,
    text: String?,
) {
    if (text != null) Text(text, color = colors.danger, fontSize = 12.sp, modifier = Modifier.testTag(tag))
}

@Composable
private fun ProxyField(
    tag: String,
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    error: Boolean,
    modifier: Modifier,
    hidden: Boolean = false,
    onTouch: () -> Unit = {},
    onFocusChange: (Boolean) -> Unit = {},
) {
    val c = colors
    val shape = RoundedCornerShape(Radius)
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    val report by rememberUpdatedState(onFocusChange)
    DisposableEffect(Unit) { onDispose { if (hadFocus) report(false) } }
    Box(
        modifier
            .height(32.dp)
            .border(1.dp, if (error) c.danger else c.border, shape)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Visible label, so a field stays identifiable once it holds a value or shows an error.
            Text(label, color = c.textSecondary, fontSize = 11.sp, maxLines = 1, modifier = Modifier.testTag("$tag-label"))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        color = c.textSecondary.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value,
                    onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = c.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(c.text),
                    visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged {
                                if (hadFocus && !it.isFocused) onTouch()
                                hadFocus = it.isFocused
                                report(it.isFocused)
                            }.then(
                                if (hidden) {
                                    // The raw value must not reach the semantics tree; SetText keeps the field drivable by tests.
                                    Modifier.clearAndSetSemantics {
                                        testTag = tag
                                        contentDescription = HIDDEN_PASSWORD
                                        password()
                                        if (error) error("Invalid value")
                                        setText {
                                            onChange(it.text)
                                            true
                                        }
                                        requestFocus {
                                            focus.requestFocus()
                                            true
                                        }
                                    }
                                } else {
                                    Modifier.testTag(tag).semantics {
                                        contentDescription = label
                                        if (error) error("Invalid value")
                                    }
                                },
                            ),
                )
            }
        }
    }
}

@Composable
private fun EyeIcon(
    color: Color,
    crossed: Boolean,
) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val st = 1.4f * density
        drawOval(color, Offset(w * .08f, w * .28f), Size(w * .84f, w * .44f), style = Stroke(st))
        drawCircle(color, w * .11f, Offset(w * .5f, w * .5f))
        if (crossed) drawLine(color, Offset(w * .15f, w * .85f), Offset(w * .85f, w * .15f), st, StrokeCap.Round)
    }
}
