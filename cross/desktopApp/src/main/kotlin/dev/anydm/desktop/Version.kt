package dev.anydm.desktop

/**
 * A release version: `1.2.3`, with an optional leading `v`, `-pre.release` part and `+build` metadata (ignored). Missing minor
 * and patch numbers are zero. Compared as semver does: by numbers, a pre-release below its release. Anything else is not one.
 */
class Version private constructor(
    private val numbers: List<Long>,
    private val preRelease: List<String>,
) : Comparable<Version> {
    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: Version): Int {
        for (i in numbers.indices) {
            val c = numbers[i].compareTo(other.numbers[i])
            if (c != 0) return c
        }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return other.preRelease.size
                .compareTo(preRelease.size)
                .coerceIn(-1, 1)
        }
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val c = compareIdentifier(preRelease[i], other.preRelease[i])
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun equals(other: Any?): Boolean = other is Version && compareTo(other) == 0

    override fun hashCode(): Int = 31 * numbers.hashCode() + preRelease.hashCode()

    override fun toString(): String = numbers.joinToString(".") + if (isPreRelease) "-" + preRelease.joinToString(".") else ""

    companion object {
        private const val MAX_LENGTH = 64
        private val shape =
            Regex(
                """[vV]?(\d{1,9})(?:\.(\d{1,9}))?(?:\.(\d{1,9}))?(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?""",
            )

        /** [text] as a version, or null when it is none. */
        fun parse(text: String): Version? {
            val trimmed = text.trim()
            if (trimmed.length > MAX_LENGTH) return null
            val match = shape.matchEntire(trimmed) ?: return null
            val (major, minor, patch, pre) = match.destructured
            val numbers = listOf(major, minor, patch).map { it.toLongOrNull() ?: 0L }
            return Version(numbers, if (pre.isEmpty()) emptyList() else pre.split('.'))
        }

        // Numbers sort below words, and by value; words sort as text.
        private fun compareIdentifier(
            a: String,
            b: String,
        ): Int {
            val x = a.toLongOrNull()
            val y = b.toLongOrNull()
            return when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1
                y != null -> 1
                else -> a.compareTo(b).coerceIn(-1, 1)
            }
        }
    }
}

/**
 * The version this app was built as: the `appVersion` in `build.gradle.kts`, written into the `app-version.txt` resource when the
 * build processes resources. Null when the resource is missing or unreadable (a run that skipped the build): the app then has no
 * version to compare releases with and must not check for updates.
 */
object AppVersion {
    val current: Version? by lazy {
        val text =
            AppVersion::class.java
                .getResourceAsStream("/app-version.txt")
                ?.use { it.readBytes().decodeToString() }
        text?.let(Version::parse)
    }
}
