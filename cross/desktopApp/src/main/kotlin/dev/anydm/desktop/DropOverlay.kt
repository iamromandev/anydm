package dev.anydm.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Covers the window while a drag is over it. Drag and drop doesn't use the pointer, so it never needs to take input. */
@Composable
fun DropOverlay() {
    val c = colors
    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg.copy(alpha = 0.82f))
            .padding(16.dp)
            .border(BorderStroke(2.dp, c.accent), RoundedCornerShape(12.dp))
            .testTag("drop-overlay")
            .semantics { contentDescription = "Drop to add" },
        contentAlignment = Alignment.Center,
    ) {
        Text("Drop to add", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

/** A short-lived message at the bottom of Home. */
@Composable
fun DropMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    val c = colors
    Box(modifier.padding(bottom = 56.dp)) {
        Text(
            text,
            color = c.text,
            fontSize = 13.sp,
            maxLines = 2,
            modifier =
                Modifier
                    .testTag("drop-message")
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
