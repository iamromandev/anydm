package dev.anydm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** The server's URL and optional key; nothing is kept until the server answers (spec: Connect). */
@Composable
fun ConnectScreen(
    initialUrl: String,
    initialKey: String?,
    message: String?,
    connecting: Boolean,
    onConnect: (url: String, key: String?) -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl.ifBlank { "http://localhost:8030" }) }
    var key by remember { mutableStateOf(initialKey.orEmpty()) }
    val submit = { if (!connecting && url.isNotBlank()) onConnect(url, key) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect to anydm", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                key,
                { key = it },
                label = { Text("API key (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (message != null) Text(message, color = MaterialTheme.colorScheme.error)
            Button(onClick = submit, enabled = !connecting) {
                if (connecting) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp))
                Text(if (connecting) "Connecting…" else "Connect")
            }
        }
    }
}
