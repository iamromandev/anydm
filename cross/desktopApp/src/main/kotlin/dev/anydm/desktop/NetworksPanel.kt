package dev.anydm.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The networks and bonding section (#609). */
@Composable
internal fun NetworksPanel(model: NetworksModel) {
    val state by model.state.collectAsState()
    val bonding by model.networks.collectAsState()
    Text(
        "Turn on two or more networks and downloads are split across them. Only networks that are up and can be bound are used.",
    )
    Text(bondingSummary(bonding), modifier = Modifier.testTag("network-summary"))
    Text(
        "Share by speed is always on: each network gets connections in proportion to how fast it has been.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("network-share"),
    )
    bonding.networks.forEach { network ->
        val key = network.info.key
        Row(
            Modifier.fillMaxWidth().testTag("network-row-$key"),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = network.enabled,
                onCheckedChange = { model.enable(key, it) },
                enabled = canSwitch(network) || network.enabled,
                modifier = Modifier.testTag("network-$key"),
            )
            Column(Modifier.weight(1f)) {
                Text(network.info.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("network-name-$key"))
                Text(
                    listOfNotNull(
                        bindingLabel(network.status),
                        if (network.info.up) null else "down",
                        network.info.addresses.firstOrNull(),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("network-status-$key"),
                )
                Text(
                    bindingHelp(network.status),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("network-help-$key"),
                )
                network.speed?.let {
                    Text(
                        "Recently ${formatSpeed(it.toLong())}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("network-speed-$key"),
                    )
                }
                if (network.faulted) {
                    Text(
                        "A bound connection failed a moment ago, so it is left out for a while.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("network-fault-$key"),
                    )
                }
            }
        }
    }
    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("network-message")) }
}
