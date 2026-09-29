package dev.anydm.desktop.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.anydm.desktop.theme.LocalTokens

/** A quiet centred icon and one line, for a list with nothing in it. */
@Composable
fun EmptyState(text: String) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Glyphs.Save, null, Modifier.size(32.dp), tint = t.secondaryText.copy(alpha = 0.6f))
        Text(text, color = t.secondaryText)
    }
}
