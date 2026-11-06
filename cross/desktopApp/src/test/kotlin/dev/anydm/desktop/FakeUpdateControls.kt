package dev.anydm.desktop

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** [UpdateControls] the tests drive by hand; [calls] records the commands as text. */
internal class FakeUpdateControls(
    initial: UpdateState = UpdateState.Idle,
    auto: Boolean = true,
) : UpdateControls {
    override val state = MutableStateFlow(initial)
    override val autoUpdate = MutableStateFlow(auto)
    private val channel = Channel<UpdateEvent>(Channel.UNLIMITED)
    override val events: Flow<UpdateEvent> = channel.receiveAsFlow()

    val calls = mutableListOf<String>()

    /** What [restart] answers when not confirmed / when confirmed. */
    var unconfirmed: RestartResult = RestartResult.Started
    var confirmed: RestartResult = RestartResult.Started

    fun emit(event: UpdateEvent) {
        channel.trySend(event)
    }

    override fun check() {
        calls += "check"
    }

    override fun download() {
        calls += "download"
    }

    override fun setAutoUpdate(on: Boolean) {
        calls += "auto $on"
        autoUpdate.value = on
    }

    override suspend fun restart(confirmed: Boolean): RestartResult {
        calls += "restart $confirmed"
        return if (confirmed) this.confirmed else unconfirmed
    }
}
