package dev.anydm.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anydm.desktop.chrome.FormField
import dev.anydm.desktop.chrome.PushButton
import dev.anydm.desktop.theme.AppIcon
import dev.anydm.desktop.theme.LocalTokens
import dev.anydm.desktop.theme.isMac

/** The server's URL and optional key; nothing is kept until the server answers (spec: Connect). */
@Composable
fun ConnectScreen(
    initialUrl: String,
    initialKey: String?,
    message: String?,
    connecting: Boolean,
    onConnect: (url: String, key: String?) -> Unit,
) {
    val t = LocalTokens.current
    var url by remember { mutableStateOf(initialUrl.ifBlank { "http://localhost:8030" }) }
    var key by remember { mutableStateOf(initialKey.orEmpty()) }
    val submit = { if (!connecting && url.isNotBlank()) onConnect(url, key) }
    // No toolbar here, so on macOS the top 28 dp stays clear of the traffic lights.
    Box(
        Modifier.fillMaxSize().background(t.content).padding(top = if (isMac()) 28.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(340.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(AppIcon, null, Modifier.size(72.dp))
            Text("Connect to anydm", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            Text("Enter the address of your anydm server.", color = t.secondaryText, textAlign = TextAlign.Center)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FormField("Server URL", url, { url = it }, tag = "url", onSubmit = submit)
                FormField("API key (optional)", key, { key = it }, tag = "key", secret = true, onSubmit = submit)
            }
            if (message != null) Text(message, color = t.error, textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PushButton(if (connecting) "Connecting…" else "Connect", primary = true, enabled = !connecting, onClick = submit)
            }
        }
    }
}
