package dev.anydm.engine.store

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.CategoryRule
import dev.anydm.engine.files.NewCategory
import dev.anydm.engine.store.db.AnydmDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import dev.anydm.engine.store.db.Category as CategoryRow

internal class SqlCategoryStore(
    private val database: AnydmDb,
    private val io: CoroutineDispatcher,
) : CategoryStore {
    private val queries = database.categoryQueries

    // A write that was asked for is made, even if the caller is cancelled meanwhile (as for the other stores).
    private val writeContext = io + NonCancellable

    override suspend fun list(): List<Category> = withContext(io) { queries.selectAll().executeAsList().map { it.toCategory() } }

    override suspend fun add(new: NewCategory): Category =
        withContext(writeContext) {
            database.transactionWithResult {
                queries.insert(new.name, new.folder, new.template, encode(new.rules), new.priority)
                queries.selectLastInserted().executeAsOne().toCategory()
            }
        }

    override suspend fun update(category: Category) {
        withContext(writeContext) {
            queries.update(category.name, category.folder, category.template, encode(category.rules), category.priority, category.id)
        }
    }

    override suspend fun remove(id: CategoryId) {
        withContext(writeContext) {
            database.transaction {
                database.downloadQueries.clearCategory(id)
                queries.delete(id)
            }
        }
    }

    private fun CategoryRow.toCategory() =
        Category(
            id = id,
            name = name,
            folder = folder,
            template = template,
            rules =
                try {
                    json.decodeFromString(rulesSerializer, rules)
                } catch (e: kotlinx.serialization.SerializationException) {
                    throw IncompatibleStoreException("category.rules of category ${id.value} doesn't parse: ${e.message}")
                },
            priority = priority,
        )

    private fun encode(rules: List<CategoryRule>): String = json.encodeToString(rulesSerializer, rules)

    private companion object {
        val json = Json
        val rulesSerializer = ListSerializer(CategoryRule.serializer())
    }
}
