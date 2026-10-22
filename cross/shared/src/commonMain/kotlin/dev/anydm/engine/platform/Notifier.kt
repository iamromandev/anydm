package dev.anydm.engine.platform

/** Shows the user a short message outside the app (OSI-09): the OS's notification centre, desktop first. */
fun interface Notifier {
    /**
     * Shows [message] under [title]. A notifier that can't (no notification service, no permission) does nothing: telling the
     * user is never worth failing for, and the caller doesn't wait on it for anything else.
     */
    suspend fun notify(
        title: String,
        message: String,
    )

    companion object {
        val None: Notifier = Notifier { _, _ -> }
    }
}
