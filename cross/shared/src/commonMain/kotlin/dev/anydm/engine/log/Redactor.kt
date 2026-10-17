package dev.anydm.engine.log

/**
 * Masks secrets in text bound for a log or a diagnostics bundle (DIA-02): cookie and authorization header values, a
 * URL's user and password, query values and JSON values whose names look secret, and the values of command-line flags
 * that carry credentials. Only the secret part becomes `***`; the rest stays readable. Masked text masks the same
 * again, so running it twice changes nothing.
 */
class Redactor(
    extraQueryNames: Set<String> = emptySet(),
) {
    private val names = (SECRET_NAMES + extraQueryNames.map { it.lowercase() }).sortedByDescending { it.length }
    private val nameGroup = names.joinToString("|") { Regex.escape(it) }

    // JSON also carries headers by name ({"Cookie": "…"}, yt-dlp's http_headers).
    private val jsonGroup = (names + HEADER_NAMES).sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private val headers = Regex("""\b(${HEADER_NAMES.joinToString("|") { Regex.escape(it) }})(\s*:\s*)("?)([^"\r\n]+)""", IGNORE_CASE)

    // The scheme is bounded, so a long run of word characters can't make this quadratic.
    private val userInfo = Regex("""\b([a-z][a-z0-9+.-]{0,31}://)([^/\s:@]+)(:[^/\s]*)?@""", IGNORE_CASE)
    private val query = Regex("""([?&;#])($nameGroup)=([^&#\s"]*)""", IGNORE_CASE)
    private val flags = Regex("""(^|[\s,\[])("?)($FLAGS)("?)(=|,\s*|\s+)("[^"]*"|'[^']*'|[^\s,\]"']+)""")
    private val json = Regex(""""($jsonGroup)"(\s*:\s*)(?:"(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?)""", IGNORE_CASE)

    fun redact(text: String): String {
        var out = text
        out = headers.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}$MASK" }
        out = userInfo.replace(out) { "${it.groupValues[1]}$MASK${if (it.groupValues[3].isEmpty()) "" else ":$MASK"}@" }
        out = query.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}=$MASK" }
        out = flags.replace(out) { m -> (1..5).joinToString("") { m.groupValues[it] } + MASK }
        out = json.replace(out) { "\"${it.groupValues[1]}\"${it.groupValues[2]}\"$MASK\"" }
        return out
    }

    private companion object {
        const val MASK = "***"
        val IGNORE_CASE = RegexOption.IGNORE_CASE

        val HEADER_NAMES = listOf("proxy-authorization", "authorization", "set-cookie", "cookie", "cookies", "x-api-key", "x-auth-token")

        val SECRET_NAMES =
            listOf(
                "token",
                "access_token",
                "refresh_token",
                "id_token",
                "auth_token",
                "accesstoken",
                "key",
                "api_key",
                "api-key",
                "apikey",
                "sig",
                "signature",
                "auth",
                "authorization",
                "pass",
                "password",
                "pwd",
                "secret",
                "client_secret",
                "session",
                "sessionid",
                "sid",
                "credential",
                "x-amz-signature",
                "x-amz-security-token",
                "x-amz-credential",
                "x-goog-signature",
                "x-goog-credential",
                "policy",
            )

        // Flags of yt-dlp, curl and friends whose value is a credential or a cookie source.
        const val FLAGS =
            "--cookies-from-browser|--cookies|--video-password|--ap-password|--ap-username|--password|--username|-p|-u"
    }
}
