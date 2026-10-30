package dev.anydm.desktop

import dev.anydm.engine.core.ProxyId
import dev.anydm.engine.net.BypassList
import dev.anydm.engine.net.NewProxy
import dev.anydm.engine.net.PasswordChange
import dev.anydm.engine.net.ProxyEntry
import dev.anydm.engine.net.ProxyType

// The proxy form (#609): text as typed, checked against the engine's own rules before anything is sent.

private const val MAX_NAME = 80

enum class ProxyField { NAME, HOST, PORT, USERNAME, PASSWORD, BYPASS }

/** What each kind of proxy can carry, for the form to say. */
val ProxyType.note: String
    get() =
        when (this) {
            ProxyType.HTTP -> "Carries web and media downloads and FTP. Torrents need a SOCKS5 proxy."
            ProxyType.HTTPS -> "Used only for FTP and SFTP, never for web or media downloads. Torrents need a SOCKS5 proxy."
            ProxyType.SOCKS5 -> "Carries everything, and is the only kind that torrents can use."
        }

val ProxyType.label: String get() = if (this == ProxyType.SOCKS5) "SOCKS5" else name

/**
 * A proxy being added ([id] null) or changed. The password is only what was typed: a saved one is not read back, so an
 * empty field keeps it, and [clearPassword] removes it.
 */
data class ProxyForm(
    val id: ProxyId? = null,
    val name: String = "",
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: String = "",
    val username: String = "",
    val password: String = "",
    val clearPassword: Boolean = false,
    val bypass: String = "",
) {
    // The generated one would print the password.
    override fun toString(): String =
        "ProxyForm(id=$id, name=$name, type=$type, host=$host, port=$port, username=$username, password hidden)"

    val bypassPatterns: List<String> get() = bypass.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    val passwordChange: PasswordChange
        get() =
            when {
                password.isNotEmpty() -> PasswordChange.Set(password)
                clearPassword -> PasswordChange.Clear
                else -> PasswordChange.Keep
            }

    fun toNew() = NewProxy(name.trim(), type, host.trim(), port.trim().toInt(), username.trim().ifEmpty { null }, bypassPatterns)

    fun toEntry(id: ProxyId) =
        ProxyEntry(id, name.trim(), type, host.trim(), port.trim().toInt(), username.trim().ifEmpty { null }, bypassPatterns)
}

fun proxyForm(proxy: ProxyEntry) =
    ProxyForm(
        id = proxy.id,
        name = proxy.name,
        type = proxy.type,
        host = proxy.host,
        port = proxy.port.toString(),
        username = proxy.username.orEmpty(),
        bypass = proxy.bypass.joinToString("\n"),
    )

fun ProxyForm.validate(): Map<ProxyField, String> =
    buildMap {
        if (name.isBlank() || name.any { it < ' ' }) {
            put(ProxyField.NAME, "Give the proxy a name on one line.")
        } else if (name.trim().length > MAX_NAME) {
            put(ProxyField.NAME, "Use 80 characters or fewer.")
        }
        val bare = host.trim()
        if (bare.isEmpty()) {
            put(ProxyField.HOST, "Enter the proxy's host name or address.")
        } else if (bare.any { it.isWhitespace() || it == '/' || it == '@' }) {
            put(ProxyField.HOST, "Only the host name, without http://, a path or a login.")
        }
        val number = port.trim().toIntOrNull()
        if (number == null || number !in 1..65535) put(ProxyField.PORT, "Enter a port from 1 to 65535.")
        if (password.isNotEmpty() && username.isBlank()) put(ProxyField.USERNAME, "A password needs a username.")
        try {
            BypassList.parse(bypassPatterns)
        } catch (e: IllegalArgumentException) {
            put(ProxyField.BYPASS, e.message ?: "A bypass pattern isn't valid.")
        }
    }
