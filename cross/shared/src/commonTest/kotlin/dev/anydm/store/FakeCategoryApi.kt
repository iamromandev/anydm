package dev.anydm.store

import dev.anydm.api.ApiException
import dev.anydm.api.CategoryApi
import dev.anydm.model.CategoryDto
import dev.anydm.model.DOWNLOADS_CATEGORY_ID

/** Categories in memory; a refusal is set in [failWith] and thrown by every call. */
class FakeCategoryApi : CategoryApi {
    var rows =
        mutableListOf(
            CategoryDto(DOWNLOADS_CATEGORY_ID, "Downloads", "downloads", "", 0, builtin = true),
            CategoryDto("c1", "Music", "music", "music", 1),
        )
    var failWith: ApiException? = null
    val ordered = mutableListOf<List<String>>()

    private fun check() {
        failWith?.let { throw it }
    }

    override suspend fun listCategories(): List<CategoryDto> {
        check()
        return rows.toList()
    }

    override suspend fun createCategory(
        name: String,
        folder: String,
    ): CategoryDto {
        check()
        return CategoryDto("n${rows.size}", name, name.lowercase(), folder, rows.size).also { rows += it }
    }

    override suspend fun updateCategory(
        id: String,
        name: String?,
        folder: String?,
    ): CategoryDto {
        check()
        val index = rows.indexOfFirst { it.id == id }
        rows[index] = rows[index].copy(name = name ?: rows[index].name, folder = folder ?: rows[index].folder)
        return rows[index]
    }

    override suspend fun orderCategories(ids: List<String>): List<CategoryDto> {
        check()
        ordered += ids
        rows = ids.mapIndexed { i, id -> rows.first { it.id == id }.copy(position = i) }.toMutableList()
        return rows.toList()
    }

    override suspend fun deleteCategory(id: String) {
        check()
        rows.removeAll { it.id == id }
    }
}
