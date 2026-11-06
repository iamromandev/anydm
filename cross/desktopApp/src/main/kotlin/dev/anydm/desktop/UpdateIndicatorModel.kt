package dev.anydm.desktop

/** Which icon the update button draws. */
enum class UpdateIcon { UP, SPINNER, ALERT }

/** What the update button shows: an [icon], optional [text] beside it, and the [label] its tooltip and screen-reader text repeat. */
data class UpdateButton(
    val icon: UpdateIcon,
    val text: String?,
    val label: String,
)

/** A command in the update menu. */
enum class UpdateAction(
    val tag: String,
) {
    RESTART("update-menu-restart"),
    DOWNLOAD("update-menu-download"),
    WEBSITE("update-menu-website"),
    CHECK("update-menu-check"),
}

data class UpdateMenuItem(
    val action: UpdateAction,
    val text: String,
    val enabled: Boolean = true,
)

/** The words of the update button and its menu for each [UpdateState]; no UI in it. */
object UpdateIndicatorModel {
    const val AUTO_LABEL = "Update Automatically"
    const val AUTO_TAG = "update-menu-auto"

    fun button(
        state: UpdateState,
        autoUpdate: Boolean,
    ): UpdateButton =
        when (state) {
            UpdateState.Idle -> {
                UpdateButton(
                    UpdateIcon.UP,
                    null,
                    if (autoUpdate) "anydm updates automatically" else "Automatic updates are off",
                )
            }

            UpdateState.Checking -> {
                UpdateButton(UpdateIcon.SPINNER, null, "Checking for updates…")
            }

            is UpdateState.Available -> {
                UpdateButton(UpdateIcon.UP, "Update", "anydm ${state.version} is available")
            }

            is UpdateState.Downloading -> {
                UpdateButton(UpdateIcon.UP, "${state.percent}%", "Downloading anydm ${state.version}… ${state.percent}%")
            }

            is UpdateState.Ready -> {
                UpdateButton(UpdateIcon.UP, "Restart to update", "anydm ${state.version} is ready — restart to update")
            }

            is UpdateState.Error -> {
                UpdateButton(UpdateIcon.ALERT, null, "Couldn't install anydm" + (state.version?.let { " $it" } ?: ""))
            }
        }

    /** The commands the menu lists above its "Update Automatically" tick box. */
    fun items(state: UpdateState): List<UpdateMenuItem> {
        val own =
            when (state) {
                UpdateState.Idle, UpdateState.Checking -> {
                    null
                }

                is UpdateState.Available -> {
                    UpdateMenuItem(UpdateAction.DOWNLOAD, "Download anydm ${state.version}")
                }

                is UpdateState.Downloading -> {
                    UpdateMenuItem(UpdateAction.DOWNLOAD, "Downloading ${state.version}…", enabled = false)
                }

                is UpdateState.Ready -> {
                    UpdateMenuItem(UpdateAction.RESTART, "Restart to Update to ${state.version}")
                }

                is UpdateState.Error -> {
                    state.pageUrl?.let {
                        UpdateMenuItem(UpdateAction.WEBSITE, "Get anydm${state.version?.let { v -> " $v" } ?: ""} from the website…")
                    }
                }
            }
        val busy = state == UpdateState.Checking || state is UpdateState.Downloading
        return listOfNotNull(own, UpdateMenuItem(UpdateAction.CHECK, "Check for Updates…", enabled = !busy))
    }
}
