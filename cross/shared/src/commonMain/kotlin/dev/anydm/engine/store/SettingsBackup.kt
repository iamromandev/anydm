package dev.anydm.engine.store

import dev.anydm.engine.core.Format
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.CategoryRule
import dev.anydm.engine.files.NewCategory
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.files.Profile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The transfer and file settings, categories and profiles as one file (#608), to move them to another machine. It holds
 * no secrets and nothing bound to this machine's other stores: no proxies, cookies or logins. A profile names its
 * category by name, since ids differ between machines.
 */
@Serializable
data class BackupFile(
    val version: Int = VERSION,
    val settings: TransferSettings = TransferSettings(),
    val categories: List<CategoryEntry> = emptyList(),
    val profiles: List<ProfileEntry> = emptyList(),
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class CategoryEntry(
    val name: String,
    val folder: String,
    val template: String? = null,
    val rules: List<CategoryRule> = emptyList(),
    val priority: Int = 0,
)

@Serializable
data class ProfileEntry(
    val name: String,
    val format: Format? = null,
    val category: String? = null,
    val saveFolder: String? = null,
    val nameTemplate: String? = null,
    val speedLimit: Long? = null,
    val isDefault: Boolean = false,
)

/** What importing a settings file came to (#608). */
sealed interface ImportResult {
    data object Applied : ImportResult

    /** Nothing was applied; one line per fault. */
    data class Refused(
        val problems: List<String>,
    ) : ImportResult
}

/** What reading a backup came to: the file, or every reason it can't be applied. */
sealed interface BackupRead {
    data class Ok(
        val file: BackupFile,
    ) : BackupRead

    data class Refused(
        val problems: List<String>,
    ) : BackupRead
}

private val backupJson =
    Json {
        prettyPrint = true
        encodeDefaults = true
    }

fun backupOf(
    settings: Settings,
    categories: List<Category>,
    profiles: List<Profile>,
): BackupFile {
    val names = categories.associate { it.id to it.name }
    return BackupFile(
        settings = settings.transfer(),
        categories = categories.map { CategoryEntry(it.name, it.folder, it.template, it.rules, it.priority) },
        profiles =
            profiles.map {
                ProfileEntry(
                    name = it.name,
                    format = it.format,
                    category = it.categoryId?.let(names::get),
                    saveFolder = it.saveFolder,
                    nameTemplate = it.nameTemplate,
                    speedLimit = it.speedLimit,
                    isDefault = it.id == settings.defaultProfileId,
                )
            },
    )
}

fun BackupFile.encode(): String = backupJson.encodeToString(this)

/**
 * Reads [text] and checks all of it, so nothing is applied from a file with any fault. A profile's category must be in
 * the file or among [existingCategories].
 */
fun readBackup(
    text: String,
    existingCategories: List<String>,
): BackupRead {
    val file =
        try {
            backupJson.decodeFromString<BackupFile>(text)
        } catch (e: SerializationException) {
            return BackupRead.Refused(listOf("not an anydm settings file: ${e.message?.lineSequence()?.first()}"))
        } catch (e: IllegalArgumentException) {
            return BackupRead.Refused(listOf("not an anydm settings file: ${e.message?.lineSequence()?.first()}"))
        }
    if (file.version != BackupFile.VERSION) {
        return BackupRead.Refused(listOf("settings file version ${file.version} is not one this anydm reads (${BackupFile.VERSION})"))
    }
    val problems = mutableListOf<String>()
    file.settings.problems().forEach { problems += "settings: $it" }
    file.categories
        .groupBy { it.name.trim().lowercase() }
        .filter { it.value.size > 1 }
        .forEach { problems += "categories: \"${it.value.first().name}\" is there more than once" }
    file.categories.forEach { entry ->
        runCatching { NewCategory(entry.name, entry.folder, entry.template, entry.rules, entry.priority) }
            .onFailure { problems += "category \"${entry.name}\": ${it.message}" }
    }
    val known = (file.categories.map { it.name } + existingCategories).map { it.trim().lowercase() }.toSet()
    file.profiles
        .groupBy { it.name }
        .filter { it.value.size > 1 }
        .forEach { problems += "profiles: \"${it.key}\" is there more than once" }
    if (file.profiles.count { it.isDefault } > 1) problems += "profiles: more than one is the default"
    file.profiles.forEach { entry ->
        runCatching { NewProfile(entry.name, entry.format, null, entry.saveFolder, entry.nameTemplate, entry.speedLimit) }
            .onFailure { problems += "profile \"${entry.name}\": ${it.message}" }
        if (entry.category != null && entry.category.trim().lowercase() !in known) {
            problems += "profile \"${entry.name}\": no category \"${entry.category}\""
        }
    }
    return if (problems.isEmpty()) BackupRead.Ok(file) else BackupRead.Refused(problems)
}
