package dev.anydm.engine.net

import java.time.ZoneId
import kotlin.time.Instant

internal actual fun localDayOf(at: Instant): String =
    java.time.Instant
        .ofEpochSecond(at.epochSeconds, at.nanosecondsOfSecond.toLong())
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toString()
