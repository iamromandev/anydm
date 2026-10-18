package dev.anydm.engine.tools

/**
 * A version made of dotted numbers (`2026.10.01`, `7.0.2`), compared number by number; a missing trailing part is 0, so
 * `7.0` equals `7.0.0`. [text] is as it was written.
 */
class ToolVersion private constructor(
    val text: String,
    private val parts: List<Int>,
) : Comparable<ToolVersion> {
    override fun compareTo(other: ToolVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = (parts.getOrNull(i) ?: 0).compareTo(other.parts.getOrNull(i) ?: 0)
            if (c != 0) return c
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is ToolVersion && compareTo(other) == 0

    override fun hashCode(): Int = parts.dropLastWhile { it == 0 }.hashCode()

    override fun toString(): String = text

    companion object {
        fun parse(text: String): ToolVersion? {
            val pieces = text.trim().split('.')
            val numbers = pieces.map { piece -> piece.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() }
            return if (numbers.any { it == null }) null else ToolVersion(text.trim(), numbers.filterNotNull())
        }

        /** The version a tool's `--version` output states, or null when it isn't in the form that tool prints. */
        fun fromOutput(
            tool: Tool,
            output: String,
        ): ToolVersion? =
            when (tool) {
                Tool.YT_DLP -> {
                    parse(output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
                }

                Tool.FFMPEG -> {
                    ffmpeg
                        .find(output)
                        ?.groupValues
                        ?.get(1)
                        ?.let(::parse)
                }

                Tool.DENO -> {
                    deno
                        .find(output)
                        ?.groupValues
                        ?.get(1)
                        ?.let(::parse)
                }
            }

        private val ffmpeg = Regex("""(?:ffmpeg|ffprobe) version (\d+(?:\.\d+)*)""")
        private val deno = Regex("""^deno (\d+(?:\.\d+)*)""", RegexOption.MULTILINE)
    }
}
