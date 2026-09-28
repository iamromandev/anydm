package dev.anydm.desktop.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anydm.desktop.theme.LocalTokens
import dev.anydm.store.Tone
import kotlinx.coroutines.delay

/** Shows [queue]'s banner: a slim card with a tone stripe, its message, its action and a close. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BannerHost(
    queue: BannerQueue,
    modifier: Modifier = Modifier,
) {
    val t = LocalTokens.current
    val banner by queue.current.collectAsState()
    LaunchedEffect(queue) {
        while (true) {
            delay(100)
            queue.tick()
        }
    }
    AnimatedVisibility(banner != null, modifier, enter = slideInVertically { it } + fadeIn(), exit = fadeOut()) {
        val shown = banner ?: return@AnimatedVisibility
        val stripe =
            when (shown.tone) {
                Tone.ERROR -> t.error
                Tone.SUCCESS -> t.ok
                Tone.INFO -> t.accent
            }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = t.bar,
            border = BorderStroke(1.dp, t.separator),
            shadowElevation = 8.dp,
            modifier =
                Modifier
                    .widthIn(max = 380.dp)
                    .onPointerEvent(PointerEventType.Enter) { queue.hover(true) }
                    .onPointerEvent(PointerEventType.Exit) { queue.hover(false) },
        ) {
            Row(
                Modifier.height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(stripe))
                Text(shown.message, Modifier.weight(1f, fill = false), color = t.text, maxLines = 2)
                shown.action?.let { label ->
                    Text(
                        label,
                        Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .clickable {
                                shown.onAction()
                                queue.dismiss()
                            }.padding(horizontal = 6.dp, vertical = 3.dp),
                        color = t.accent,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    "✕",
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable {
                            queue.dismiss()
                        }.padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 11.sp,
                    color = t.secondaryText,
                )
            }
        }
    }
}
