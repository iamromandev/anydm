package dev.anydm.engine.files

import dev.anydm.engine.core.DownloadKind
import kotlinx.serialization.Serializable

/** What a download is, as far as a [CategoryRule] can tell once the probe has answered. */
data class SourceFacts(
    val name: String,
    val mime: String?,
    val host: String?,
    val kind: DownloadKind,
)

/** One way a category claims a download (FIL-01). Stored as JSON in the `category` table. */
@Serializable
sealed interface CategoryRule {
    fun matches(facts: SourceFacts): Boolean

    /** File extensions without the dot, any case. */
    @Serializable
    data class Extension(
        val extensions: Set<String>,
    ) : CategoryRule {
        override fun matches(facts: SourceFacts): Boolean {
            val own = facts.name.substringAfterLast('.', "").lowercase()
            return own.isNotEmpty() && extensions.any { it.trimStart('.').lowercase() == own }
        }
    }

    /** MIME types: an exact type, or a family written as the type's first part, a slash and a star. */
    @Serializable
    data class Mime(
        val patterns: Set<String>,
    ) : CategoryRule {
        override fun matches(facts: SourceFacts): Boolean {
            val mime = facts.mime?.lowercase() ?: return false
            return patterns.any {
                val pattern = it.lowercase()
                if (pattern.endsWith("/*")) mime.startsWith(pattern.removeSuffix("*")) else mime == pattern
            }
        }
    }

    /** A host name and every subdomain of it. */
    @Serializable
    data class Domain(
        val domains: Set<String>,
    ) : CategoryRule {
        override fun matches(facts: SourceFacts): Boolean {
            val host = facts.host?.lowercase() ?: return false
            return domains.any {
                val domain = it.lowercase()
                host == domain || host.endsWith(".$domain")
            }
        }
    }

    @Serializable
    data class Kind(
        val kinds: Set<DownloadKind>,
    ) : CategoryRule {
        override fun matches(facts: SourceFacts): Boolean = facts.kind in kinds
    }
}
