package dev.anydm.desktop

import dev.anydm.engine.core.DownloadId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The page the window shows. */
sealed interface Route {
    /** The landing page: one field for a link. */
    data object Landing : Route

    /** The list of downloads. */
    data object Downloads : Route

    /** One download's page. */
    data class Detail(
        val id: DownloadId,
    ) : Route
}

/** Where the window is: it starts on the landing page, opening a download moves to its page, back goes one step towards the landing page. */
class Navigator {
    private val current = MutableStateFlow<Route>(Route.Landing)
    val route: StateFlow<Route> = current

    fun landing() {
        current.value = Route.Landing
    }

    fun downloads() {
        current.value = Route.Downloads
    }

    fun open(id: DownloadId) {
        current.value = Route.Detail(id)
    }

    fun back() {
        current.value =
            when (current.value) {
                is Route.Detail -> Route.Downloads
                Route.Downloads, Route.Landing -> Route.Landing
            }
    }
}
