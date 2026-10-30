package dev.anydm.engine.net

import kotlin.time.Instant

/** The calendar day of [at] in the system's time zone, `YYYY-MM-DD`: where daily traffic splits (NET-04). */
internal expect fun localDayOf(at: Instant): String
