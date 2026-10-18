package dev.anydm.engine.files

/** The placeholders a naming template knows (FIL-02). */
enum class TemplateField(
    val token: String,
) {
    TITLE("title"),
    UPLOADER("uploader"),
    ID("id"),
    DATE("date"),
    RESOLUTION("resolution"),
    PLAYLIST_INDEX("playlist_index"),
}

/** What a download knows about itself. A field it can't supply is left out. */
data class NameFields(
    val values: Map<TemplateField, String> = emptyMap(),
)

sealed interface TemplateParse {
    data class Ok(
        val template: NameTemplate,
    ) : TemplateParse

    /** The template names a field that doesn't exist; shown to the user as it was typed. */
    data class UnknownField(
        val name: String,
    ) : TemplateParse
}

/**
 * A file-name template such as `{playlist_index}. {title} [{id}]`. [render] gives the stem only; the caller adds the
 * extension and runs [NameCleaner]. A field with no value is dropped together with the separator before it (or after
 * it, when it comes first), so there is no `- -` left over.
 */
class NameTemplate private constructor(
    private val parts: List<Part>,
) {
    private sealed interface Part {
        data class Literal(
            val text: String,
        ) : Part

        data class Field(
            val field: TemplateField,
        ) : Part
    }

    fun render(fields: NameFields): String {
        val live = parts.toMutableList<Part?>()
        var dropped = false
        for ((index, part) in parts.withIndex()) {
            if (part !is Part.Field || fields.values[part.field] != null) continue
            dropped = true
            live[index] = null
            val before = live.getOrNull(index - 1)
            val firstField = live.take(index).none { it is Part.Field }
            if (before is Part.Literal && before.isSeparator()) {
                live[index - 1] = null
            } else if (firstField) {
                val after = live.getOrNull(index + 1)
                if (after is Part.Literal && after.isSeparator()) live[index + 1] = null
            }
        }
        val text =
            live.joinToString("") {
                when (it) {
                    is Part.Literal -> it.text
                    is Part.Field -> fields.values.getValue(it.field)
                    null -> ""
                }
            }
        return if (dropped) text.trim { !it.isLetterOrDigit() } else text
    }

    private fun Part.Literal.isSeparator() = text.none { it.isLetterOrDigit() }

    companion object {
        private val placeholder = Regex("\\{([^{}]*)}")

        fun parse(text: String): TemplateParse {
            val parts = mutableListOf<Part>()
            var last = 0
            for (match in placeholder.findAll(text)) {
                if (match.range.first > last) parts += Part.Literal(text.substring(last, match.range.first))
                val name = match.groupValues[1]
                val field = TemplateField.entries.firstOrNull { it.token == name } ?: return TemplateParse.UnknownField(name)
                parts += Part.Field(field)
                last = match.range.last + 1
            }
            if (last < text.length) parts += Part.Literal(text.substring(last))
            return TemplateParse.Ok(NameTemplate(parts))
        }
    }
}
