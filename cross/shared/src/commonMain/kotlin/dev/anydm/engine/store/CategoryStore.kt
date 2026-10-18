package dev.anydm.engine.store

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.NewCategory

/** The categories downloads are sorted into (FIL-01). */
interface CategoryStore {
    /** Every category, lowest priority number first, then by id. */
    suspend fun list(): List<Category>

    suspend fun add(new: NewCategory): Category

    /** Replaces the stored category with the same id; one that isn't there is left alone. */
    suspend fun update(category: Category)

    /** Removes the category and clears it from every download that had it; their files stay where they are. */
    suspend fun remove(id: CategoryId)
}
