package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun Sidebar(
    state: UiState,
    onNavigate: (Route) -> Unit,
) {
    Column(
        Modifier
            .width(220.dp)
            .fillMaxHeight()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val route = state.route
        Entry("All downloads", "all", state.counts.all, route == Route.Downloads(Filter.All)) { onNavigate(Route.Downloads(Filter.All)) }
        StatusGroup.entries.forEach { group ->
            val target = Route.Downloads(Filter.Status(group))
            Entry(group.label, group.name.lowercase(), state.counts[group], route == target) { onNavigate(target) }
        }
        if (state.categories.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Categories",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            state.categories.forEach { category ->
                val target = Route.Downloads(Filter.InCategory(category.id))
                Entry(
                    category.name,
                    "category-${category.id.value}",
                    state.counts.category(category.id),
                    route == target,
                ) { onNavigate(target) }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { onNavigate(Route.Settings) }
                .testTag("nav-settings")
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                "Settings",
                color =
                    if (route ==
                        Route.Settings
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
        }
    }
}

@Composable
private fun Entry(
    label: String,
    key: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .testTag("nav-$key")
            .let { if (selected) it.background(colors.primaryContainer) else it }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) colors.onPrimaryContainer else colors.onSurface)
        Text(
            count.toString(),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("count-$key"),
        )
    }
}
