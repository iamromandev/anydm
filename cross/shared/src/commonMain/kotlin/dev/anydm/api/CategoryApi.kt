package dev.anydm.api

import dev.anydm.model.CategoryDto

/** What `CategoryStore` asks of the API. `AnydmApi` implements it; tests fake it. */
interface CategoryApi {
    suspend fun listCategories(): List<CategoryDto>

    suspend fun createCategory(
        name: String,
        folder: String,
    ): CategoryDto

    /** Only the non-null fields are sent. */
    suspend fun updateCategory(
        id: String,
        name: String?,
        folder: String?,
    ): CategoryDto

    suspend fun orderCategories(ids: List<String>): List<CategoryDto>

    suspend fun deleteCategory(id: String)
}
