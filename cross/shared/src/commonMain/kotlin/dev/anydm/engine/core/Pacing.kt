package dev.anydm.engine.core

import kotlinx.serialization.Serializable

/** How careful requests to a site are (MED-20): not at all, or with pauses between them. */
@Serializable
enum class PacingPreset { OFF, BALANCED, CAREFUL, CUSTOM }

/** The pauses, in seconds: between requests, and between one download and the next (a random time from [intervalMin] to [intervalMax]). */
@Serializable
data class PacingValues(
    val requestSleep: Double,
    val intervalMin: Double,
    val intervalMax: Double,
) {
    init {
        require(requestSleep in 0.0..MAX_SECONDS) { "requestSleep must be in 0..$MAX_SECONDS: $requestSleep" }
        require(intervalMin in 0.0..MAX_SECONDS) { "intervalMin must be in 0..$MAX_SECONDS: $intervalMin" }
        require(intervalMax in 0.0..MAX_SECONDS) { "intervalMax must be in 0..$MAX_SECONDS: $intervalMax" }
        require(intervalMin <= intervalMax) { "intervalMin must not exceed intervalMax" }
    }

    private companion object {
        const val MAX_SECONDS = 120.0
    }
}

/** A preset, or the [custom] values when the preset is CUSTOM. */
@Serializable
data class Pacing(
    val preset: PacingPreset = PacingPreset.OFF,
    val custom: PacingValues = PacingValues(0.0, 0.0, 0.0),
) {
    fun values(): PacingValues =
        when (preset) {
            PacingPreset.OFF -> PacingValues(0.0, 0.0, 0.0)
            PacingPreset.BALANCED -> PacingValues(1.0, 1.0, 3.0)
            PacingPreset.CAREFUL -> PacingValues(3.0, 5.0, 15.0)
            PacingPreset.CUSTOM -> custom
        }
}

/** yt-dlp's `--sleep-*` arguments for these pauses; a pause of zero adds none. */
fun PacingValues.ytDlpFlags(): List<String> =
    buildList {
        if (requestSleep > 0) addAll(listOf("--sleep-requests", requestSleep.toString()))
        if (intervalMin > 0) {
            addAll(listOf("--sleep-interval", intervalMin.toString()))
            if (intervalMax > intervalMin) addAll(listOf("--max-sleep-interval", intervalMax.toString()))
        }
        if (requestSleep > 0) addAll(listOf("--sleep-subtitles", requestSleep.toString()))
    }
