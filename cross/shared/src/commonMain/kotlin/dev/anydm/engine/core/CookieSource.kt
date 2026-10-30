package dev.anydm.engine.core

import kotlinx.io.files.Path

/**
 * Where yt-dlp finds the user's cookies (MED-18): a cookies file, or a browser's own store. Only this pointer is kept; the
 * cookies stay where they are (the spec: cookies are never stored by us as plain text).
 */
sealed interface CookieSource {
    data object None : CookieSource

    /** A Netscape-format cookies file at an absolute [path]. */
    data class File(
        val path: String,
    ) : CookieSource {
        init {
            require(path.isNotEmpty() && '\u0000' !in path && '\n' !in path && '\r' !in path) { "the cookies file path is not usable" }
            require(Path(path).isAbsolute) { "the cookies file path must be absolute" }
        }
    }

    /** A browser yt-dlp can read cookies from, and optionally which of its profiles. */
    data class Browser(
        val name: String,
        val profile: String? = null,
    ) : CookieSource {
        init {
            require(name in BROWSERS) { "unknown browser: $name" }
            require(profile == null || (profile.isNotBlank() && ':' !in profile && '\n' !in profile && !profile.startsWith("-"))) {
                "the browser profile is not usable"
            }
        }
    }

    /** The arguments that point yt-dlp at this source. */
    fun ytDlpFlags(): List<String> =
        when (this) {
            None -> emptyList()
            is File -> listOf("--cookies", path)
            is Browser -> listOf("--cookies-from-browser", if (profile != null) "$name:$profile" else name)
        }

    companion object {
        val BROWSERS: Set<String> = setOf("brave", "chrome", "chromium", "edge", "firefox", "opera", "safari", "vivaldi", "whale")

        /** `file:<path>` or `browser:<name>[:<profile>]`; None has no text. */
        fun encode(source: CookieSource): String =
            when (source) {
                None -> ""
                is File -> "file:${source.path}"
                is Browser -> "browser:${source.name}" + (source.profile?.let { ":$it" } ?: "")
            }

        /** The source [encode] wrote, or null for anything else. */
        fun decode(text: String): CookieSource? =
            try {
                when {
                    text.startsWith("file:") -> {
                        File(text.removePrefix("file:"))
                    }

                    text.startsWith("browser:") -> {
                        val rest = text.removePrefix("browser:")
                        val name = rest.substringBefore(':')
                        val profile = rest.substringAfter(':', "")
                        if (':' in rest && profile.isEmpty()) null else Browser(name, profile.ifEmpty { null })
                    }

                    else -> {
                        null
                    }
                }
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
