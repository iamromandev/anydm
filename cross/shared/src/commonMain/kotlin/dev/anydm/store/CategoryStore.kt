package dev.anydm.store

import dev.anydm.api.CategoryApi
import dev.anydm.model.CategoryDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CategoryState(
    val categories: List<CategoryDto> = emptyList(),
    val loaded: Boolean = false,
    /** The last refusal, in the server's words, until the next action or `clearError`. */
    val error: String? = null,
    val busy: Boolean = false,
)

/** The categories and every edit to them. web: the route's category handlers. */
class CategoryStore(
    private val api: CategoryApi,
) {
    private val mutableState = MutableStateFlow(CategoryState())
    val state: StateFlow<CategoryState> = mutableState.asStateFlow()

    suspend fun load() {
        attempt {
            val listed = api.listCategories()
            mutableState.update { it.copy(categories = listed, loaded = true) }
        }
    }

    suspend fun create(
        name: String,
        folder: String,
    ): Boolean = attempt { api.createCategory(name, folder) }.also { if (it) load() }

    suspend fun update(
        id: String,
        name: String?,
        folder: String?,
    ): Boolean = attempt { api.updateCategory(id, name, folder) }.also { if (it) load() }

    /** Swaps a category with its neighbour; false at either end. */
    suspend fun move(
        index: Int,
        delta: Int,
    ): Boolean {
        val ids = state.value.categories.map { it.id }
        val other = index + delta
        if (other !in ids.indices) return false
        val next = ids.toMutableList().also { list -> list[index] = ids[other].also { list[other] = ids[index] } }
        return attempt {
            val ordered = api.orderCategories(next)
            mutableState.update { it.copy(categories = ordered) }
        }
    }

    suspend fun delete(id: String): Boolean = attempt { api.deleteCategory(id) }.also { if (it) load() }

    fun clearError() = mutableState.update { it.copy(error = null) }

    /** Runs one edit: busy while it runs, and its refusal kept in [CategoryState.error]. */
    private suspend fun attempt(block: suspend () -> Unit): Boolean {
        mutableState.update { it.copy(busy = true, error = null) }
        return try {
            block()
            mutableState.update { it.copy(busy = false) }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(busy = false, error = error.message ?: "Something went wrong") }
            false
        }
    }
}
