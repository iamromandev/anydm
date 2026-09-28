package dev.anydm.desktop.chrome

import dev.anydm.store.Tone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A notice in the window's corner; [action] (e.g. "Reveal") keeps it up longer. */
data class Banner(
    val tone: Tone,
    val message: String,
    val action: String? = null,
    val onAction: () -> Unit = {},
)

/**
 * One banner at a time, the rest waiting. Each lasts [SHORT_MS], or [LONG_MS] with an action;
 * hovering stops the clock. [tick] is called by the host a few times a second.
 */
class BannerQueue(
    private val now: () -> Long,
) {
    private val waiting = ArrayDeque<Banner>()
    private val shown = MutableStateFlow<Banner?>(null)
    val current: StateFlow<Banner?> = shown

    private var left = 0L
    private var since = 0L
    private var held = false

    fun push(banner: Banner) {
        waiting.addLast(banner)
        if (shown.value == null) next()
    }

    fun hover(on: Boolean) {
        if (on == held || shown.value == null) {
            held = on
            return
        }
        if (on) left -= now() - since else since = now()
        held = on
    }

    fun dismiss() = next()

    fun tick() {
        if (shown.value != null && !held && now() - since >= left) next()
    }

    private fun next() {
        val banner = waiting.removeFirstOrNull()
        shown.value = banner
        held = false
        since = now()
        left = if (banner?.action != null) LONG_MS else SHORT_MS
    }

    companion object {
        const val SHORT_MS = 4_000L
        const val LONG_MS = 8_000L
    }
}
