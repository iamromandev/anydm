package dev.anydm.desktop

import dev.anydm.engine.core.DownloadId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The page the window shows. */
sealed interface Route {
    /** The list of downloads. */
    data object Home : Route

    /** One download's page. */
    data class Detail(
        val id: DownloadId,
    ) : Route
}

/** Where the window is: opening a download moves to its page, back returns to the list. */
class Navigator {
    private val current = MutableStateFlow<Route>(Route.Home)
    val route: StateFlow<Route> = current

    fun open(id: DownloadId) {
        current.value = Route.Detail(id)
    }

    fun back() {
        current.value = Route.Home
    }
}
