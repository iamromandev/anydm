package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.anydm.engine.EngineState

/** The window's content: toolbar, sidebar, banners, the current section, status bar and, once, the welcome. */
@Composable
fun Shell(model: AppModel) {
    val state by model.state.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { model.handleKey(it) },
        color = MaterialTheme.colorScheme.background,
    ) {
        Box {
            Column(Modifier.fillMaxSize()) {
                Toolbar(state.route)
                Row(Modifier.weight(1f)) {
                    Sidebar(state, model::navigate)
                    Column(Modifier.weight(1f).fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        Banners(state.banners, model::dismiss)
                        Box(Modifier.weight(1f).fillMaxWidth()) { Content(state) }
                        StatusBar(state)
                    }
                }
            }
            if (state.showWelcome) Welcome(model::finishWelcome)
        }
    }
}

@Composable
private fun Toolbar(route: Route) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("anydm", style = MaterialTheme.typography.titleMedium)
        Text(
            "  ·  " + title(route),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun title(route: Route): String =
    when (route) {
        Route.Settings -> {
            "Settings"
        }

        is Route.Downloads -> {
            when (val filter = route.filter) {
                Filter.All -> "All downloads"
                is Filter.Status -> filter.group.label
                is Filter.InCategory -> "Category"
            }
        }
    }

@Composable
private fun Content(state: UiState) {
    when (val route = state.route) {
        Route.Settings -> {
            Placeholder("Settings arrive with the transfer, network and tool settings stories.", "settings")
        }

        // The list itself is the next story; the shell shows what it will fill.
        is Route.Downloads -> {
            Placeholder(
                if (state.counts.of(route.filter) == 0) "Nothing here yet." else "The download list arrives in the next story.",
                "empty",
            )
        }
    }
}

@Composable
private fun Placeholder(
    text: String,
    tag: String,
) {
    Box(Modifier.fillMaxSize().testTag(tag), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Banners(
    banners: List<Banner>,
    onDismiss: (Banner) -> Unit,
) {
    banners.forEach { banner ->
        val (tag, text) =
            when (banner) {
                is Banner.EngineUnavailable -> {
                    "banner-engine" to
                        "The download engine is unavailable: ${banner.reason}. Restart anydm to try again."
                }

                Banner.DiskFull -> {
                    "banner-disk" to "The disk is full. Downloads are paused until there is room."
                }

                is Banner.Error -> {
                    "banner-error" to banner.message
                }
            }
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.errorContainer,
                ).padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag(tag),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = { onDismiss(banner) }, modifier = Modifier.testTag("banner-dismiss")) { Text("Dismiss") }
        }
    }
}

@Composable
private fun StatusBar(state: UiState) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(engineLabel(state.engine), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("status-engine"))
        Text(formatSpeed(state.speed), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("status-speed"))
    }
}

internal fun engineLabel(state: EngineState): String =
    when (state) {
        EngineState.NotStarted -> "Starting"
        EngineState.Running -> "Running"
        EngineState.Stopping -> "Stopping"
        EngineState.Stopped -> "Stopped"
        is EngineState.Failed -> "Unavailable"
    }

internal fun formatSpeed(bytesPerSecond: Long): String {
    val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSecond.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytesPerSecond B/s" else "%.1f %s".format(value, units[unit])
}

@Composable
private fun Welcome(onStart: () -> Unit) {
    Box(
        // The pointer handler keeps clicks from reaching the window underneath.
        Modifier
            .fillMaxSize()
            .background(
                MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
            ).pointerInput(Unit) {}
            .testTag("welcome"),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.padding(32.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Welcome to anydm", style = MaterialTheme.typography.headlineSmall)
                Text("Downloads go to your Downloads folder unless a category says otherwise. You can change this in Settings.")
                Button(onClick = onStart, modifier = Modifier.testTag("welcome-start")) { Text("Get started") }
            }
        }
    }
}
