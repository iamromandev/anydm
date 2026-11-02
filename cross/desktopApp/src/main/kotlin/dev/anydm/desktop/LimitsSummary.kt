package dev.anydm.desktop

/** One page's lines in the dialog's sidebar; [reached] when the network is over its data limit. */
data class PageSummary(
    val lines: List<String>,
    val reached: Boolean = false,
)

/** A time of day as `07:05`. */
internal fun clockText(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

/** What the sidebar says under All downloads: slow mode or the total limit or none, and the schedule's window when it is on. */
internal fun generalSummary(
    draft: GeneralDraft,
    unit: SpeedUnit,
): PageSummary {
    val total = if (draft.totalOn) amountBytes(draft.totalText, unit.unitBytes) else null
    val first =
        when {
            draft.slowOn -> "Slow mode on"
            total != null -> "Limit ${formatRate(total, unit)}"
            else -> "No limit"
        }
    val schedule = draft.schedule.takeIf { it.enabled && it.valid }?.let { "${clockText(it.startMinute)}–${clockText(it.endMinute)}" }
    return PageSummary(listOfNotNull(first, schedule))
}

/** What the sidebar says under a network: that it reached its data limit, else a line each for its limits, else No limit. */
internal fun networkSummary(
    draft: NetworkDraft,
    usage: NetworkUsage?,
    unit: SpeedUnit,
): PageSummary {
    val speed = if (draft.speedOn) amountBytes(draft.speedText, unit.unitBytes) else null
    val data = if (draft.dataOn) amountBytes(draft.dataText, GIB) else null
    if (data != null && usage != null && usage.bytes >= data) return PageSummary(listOf("Data limit reached"), reached = true)
    val lines =
        listOfNotNull(
            speed?.let { "Limit ${formatRate(it, unit)}" },
            data?.let { "${formatBytes(usage?.bytes ?: 0)} of ${formatBytes(it)} ${draft.period.phrase}" },
        )
    return PageSummary(lines.ifEmpty { listOf("No limit") })
}
