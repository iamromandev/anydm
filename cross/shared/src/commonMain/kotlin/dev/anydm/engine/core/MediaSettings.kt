package dev.anydm.engine.core

/** The global settings a media download reads when it runs: where cookies come from (MED-18) and how requests are paced (MED-20). */
data class MediaSettings(
    val cookieSource: CookieSource = CookieSource.None,
    val pacing: Pacing = Pacing(),
)
