package dev.anydm.desktop.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anydm.desktop.theme.LocalTokens

/** A native push button: a filled primary (the selection blue, or [fill]) or a quiet bordered one. */
@Composable
fun PushButton(
    label: String,
    primary: Boolean = false,
    fill: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(if (primary) fill ?: t.selection else t.content)
            .border(1.dp, if (primary) Color.Transparent else t.separator, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) Color.White else t.text, fontWeight = FontWeight.Medium)
    }
}
