package dev.anydm.engine.platform

/** Records "sleep" and "shutDown" instead of running them; [before] runs first. */
internal class FakePowerActions(
    var result: Boolean = true,
) : PowerActions {
    val calls = mutableListOf<String>()
    var before: (suspend (String) -> Unit)? = null

    private suspend fun record(name: String): Boolean {
        before?.invoke(name)
        calls += name
        return result
    }

    override suspend fun sleep() = record("sleep")

    override suspend fun shutDown() = record("shutDown")
}
