package dev.anydm.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** Where the settings live: a file on the desktop, memory in a test. */
interface SettingsFile {
    fun read(): String?

    fun write(text: String)
}

private val SettingsJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

/** The settings, read once and written on every change. A damaged file reads as the defaults. */
class SettingsStore(
    private val file: SettingsFile,
) {
    private val mutable = MutableStateFlow(load())
    val settings: StateFlow<Settings> = mutable.asStateFlow()

    fun update(change: (Settings) -> Settings) {
        val next = change(mutable.value)
        mutable.value = next
        file.write(SettingsJson.encodeToString(Settings.serializer(), next))
    }

    private fun load(): Settings =
        file.read()?.let { text -> runCatching { SettingsJson.decodeFromString(Settings.serializer(), text) }.getOrNull() }
            ?: Settings()
}
