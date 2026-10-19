package dev.anydm.engine.files

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.ProfileId
import kotlinx.io.files.Path

private const val MAX_NAME = 80

private fun check(
    name: String,
    saveFolder: String?,
    nameTemplate: String?,
    speedLimit: Long?,
) {
    require(name.isNotBlank() && name.length <= MAX_NAME) { "name must be 1 to $MAX_NAME characters" }
    require(name.none { it < ' ' || it == ' ' || it == ' ' }) { "name must be on one line" }
    require(saveFolder == null || Path(saveFolder).isAbsolute) { "saveFolder must be absolute: $saveFolder" }
    require(nameTemplate == null || NameTemplate.parse(nameTemplate) is TemplateParse.Ok) { "nameTemplate doesn't parse: $nameTemplate" }
    require(speedLimit == null || speedLimit > 0) { "speedLimit must be > 0, or null for none: $speedLimit" }
}

/**
 * A named bundle of download options (SET-02). A job that names it reads these live: its own value wins, else the
 * profile's, else the global default. A null field leaves that choice to the job or the default.
 */
data class Profile(
    val id: ProfileId,
    val name: String,
    val format: Format?,
    val categoryId: CategoryId?,
    val saveFolder: String?,
    val nameTemplate: String?,
    /** Bytes per second. */
    val speedLimit: Long?,
) {
    init {
        check(name, saveFolder, nameTemplate, speedLimit)
    }
}

/** A [Profile] to add; the store assigns its id. Same rules as [Profile]. */
data class NewProfile(
    val name: String,
    val format: Format? = null,
    val categoryId: CategoryId? = null,
    val saveFolder: String? = null,
    val nameTemplate: String? = null,
    val speedLimit: Long? = null,
) {
    init {
        check(name, saveFolder, nameTemplate, speedLimit)
    }
}

/** What saving a profile came to. */
sealed interface ProfileResult {
    data class Saved(
        val profile: Profile,
    ) : ProfileResult

    /** Another profile has the name. */
    data object NameTaken : ProfileResult

    /** An update of a profile that no longer exists. */
    data object NotFound : ProfileResult
}
