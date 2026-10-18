package dev.anydm.engine.files

import dev.anydm.engine.core.CategoryId
import kotlinx.io.files.Path

/**
 * A place downloads go (FIL-01): its [folder], an optional naming [template] and the [rules] that claim a download.
 * A lower [priority] number is asked first.
 */
data class Category(
    val id: CategoryId,
    val name: String,
    val folder: String,
    val template: String?,
    val rules: List<CategoryRule>,
    val priority: Int,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(Path(folder).isAbsolute) { "folder must be absolute: $folder" }
        require(template == null || NameTemplate.parse(template) is TemplateParse.Ok) { "template doesn't parse: $template" }
    }
}

/** A [Category] to add; the store assigns its id. Same rules as [Category]. */
data class NewCategory(
    val name: String,
    val folder: String,
    val template: String?,
    val rules: List<CategoryRule>,
    val priority: Int,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(Path(folder).isAbsolute) { "folder must be absolute: $folder" }
        require(template == null || NameTemplate.parse(template) is TemplateParse.Ok) { "template doesn't parse: $template" }
    }
}
