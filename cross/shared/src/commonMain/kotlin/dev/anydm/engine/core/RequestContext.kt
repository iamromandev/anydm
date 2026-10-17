package dev.anydm.engine.core

/**
 * What a download's requests carry besides the URL (HTTP-10): extra headers, the referrer, the user agent and cookies.
 * Stored with the download so a resume after a restart asks the same way. Cookies live here until the keychain takes
 * them over (#542); logs mask them (#556). Only what HTTP can send is accepted: header names are tokens, and every
 * value is visible ASCII, spaces and tabs, so nothing can split into another header.
 */
data class RequestContext(
    val headers: List<Pair<String, String>> = emptyList(),
    val referrer: String? = null,
    val userAgent: String? = null,
    val cookies: String? = null,
) {
    init {
        for ((name, value) in headers) {
            require(token.matches(name)) { "header name '$name' isn't an HTTP token" }
            requireSendable("header $name", value)
        }
        referrer?.let { requireSendable("referrer", it) }
        userAgent?.let { requireSendable("user agent", it) }
        cookies?.let { requireSendable("cookies", it) }
    }

    private companion object {
        val token = Regex("""[!#$%&'*+.^_`|~0-9A-Za-z-]+""")

        /** The value itself stays out of the message: it may be a secret. */
        fun requireSendable(
            what: String,
            value: String,
        ) = require(value.all { it == '\t' || it in ' '..'~' }) { "$what holds a character HTTP can't send" }
    }
}
