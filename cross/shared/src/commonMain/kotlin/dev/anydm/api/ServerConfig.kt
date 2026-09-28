package dev.anydm.api

/** Which anydm server, and the key it wants: `API_KEY` in its `api/.env`, or none. */
data class ServerConfig(
    val baseUrl: String,
    val apiKey: String?,
)

/** The URL without a trailing slash; a blank key counts as none. */
fun ServerConfig.normalized(): ServerConfig =
    ServerConfig(
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() },
    )
