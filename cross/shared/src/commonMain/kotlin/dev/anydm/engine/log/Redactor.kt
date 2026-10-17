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

    private val headers = Regex("""(?i)\b(Proxy-Authorization|Authorization|Set-Cookie|Cookie)(\s*:\s*)([^"\r\n]+)""")
    private val userInfo = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)([^/\s:@]+)(:[^/\s@]*)?@""")
    private val query = Regex("""(?i)([?&;])($nameGroup)=([^&#\s"]*)""")
    private val flags = Regex("""(^|\s)($FLAGS)(=|\s+)("[^"]*"|\S+)""")
    private val json = Regex("""(?i)"($nameGroup)"(\s*:\s*)"(?:[^"\\]|\\.)*"""")

    fun redact(text: String): String {
        var out = text
        out = headers.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        out = userInfo.replace(out) { "${it.groupValues[1]}$MASK${if (it.groupValues[3].isEmpty()) "" else ":$MASK"}@" }
        out = query.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}=$MASK" }
        out = flags.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}$MASK" }
        out = json.replace(out) { "\"${it.groupValues[1]}\"${it.groupValues[2]}\"$MASK\"" }
        return out
    }

    private companion object {
        const val MASK = "***"

        val SECRET_NAMES =
            listOf(
                "token",
                "access_token",
                "refresh_token",
                "id_token",
                "key",
                "api_key",
                "apikey",
                "sig",
                "signature",
                "auth",
                "authorization",
                "pass",
                "password",
                "pwd",
                "secret",
                "session",
                "sessionid",
                "sid",
                "credential",
                "x-amz-signature",
                "x-amz-security-token",
                "x-amz-credential",
                "policy",
            )

        // Flags of yt-dlp, curl and friends whose value is a credential or a cookie source.
        const val FLAGS =
            "--cookies-from-browser|--cookies|--video-password|--ap-password|--ap-username|--password|--username|-p|-u"
    }
}
