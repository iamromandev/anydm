package dev.anydm.engine.core

/**
 * What a download's requests carry besides the URL (HTTP-10): extra headers, the referrer, the user agent and cookies.
 * Stored with the download so a resume after a restart asks the same way. Cookies live here until the keychain takes
 * them over (#542); logs mask them (#556).
 */
data class RequestContext(
    val headers: List<Pair<String, String>> = emptyList(),
    val referrer: String? = null,
    val userAgent: String? = null,
    val cookies: String? = null,
)
