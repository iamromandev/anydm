package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun Sidebar(
    state: UiState,
    onProtocol: (Protocol?) -> Unit,
    onNavigate: (Route) -> Unit,
) {
    Column(
        Modifier
            .width(220.dp)
            .fillMaxHeight()
            .padding(Space.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val route = state.route
        Entry("All downloads", "all", state.counts.all, route == Route.Downloads(Filter.All)) { onNavigate(Route.Downloads(Filter.All)) }
        StatusGroup.entries.forEach { group ->
            val target = Route.Downloads(Filter.Status(group))
            Entry(group.label, group.name.lowercase(), state.counts[group], route == target) { onNavigate(target) }
        }
        SectionHeader("Protocols")
        Protocol.entries.forEach { protocol ->
            val selected = route is Route.Downloads && state.options.protocol == protocol
            Entry(
                protocol.label,
                "protocol-${protocol.name.lowercase()}",
                state.counts.protocol(protocol),
                selected,
            ) { onProtocol(if (state.options.protocol == protocol) null else protocol) }
        }
        if (state.categories.isNotEmpty()) {
            SectionHeader("Categories")
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
        Entry("Settings", "settings", null, route == Route.Settings) { onNavigate(Route.Settings) }
    }
}

@Composable
private fun Entry(
    label: String,
    key: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag("nav-$key")
            .let { if (selected) it.background(colors.primaryContainer) else it }
            .padding(horizontal = Space.sm, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
        )
        if (count != null) {
            Text(
                count.toString(),
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("count-$key"),
            )
        }
    }
}
