package dev.anydm.store

import dev.anydm.api.BatchApi
import dev.anydm.model.BatchItem
import dev.anydm.model.BatchKind
import dev.anydm.model.BatchPreview
import dev.anydm.model.toItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How long typing has to pause before the preview is asked for again. */
const val BATCH_PREVIEW_DELAY_MS = 400L

/** Everything the Add many dialog draws. */
data class BatchState(
    val kind: BatchKind = BatchKind.LIST,
    val list: String = "",
    val pattern: String = "",
    val checking: Boolean = false,
    val preview: BatchPreview? = null,
    /** The API's refusal of the preview: a backwards range, more than 1,000 links. */
    val previewError: String? = null,
    val adding: Boolean = false,
    val addError: String? = null,
    /** Every link's answer, once added; the dialog shows these instead of the form. */
    val results: List<BatchItem>? = null,
) {
    val text: String get() = if (kind == BatchKind.LIST) list else pattern

    val canAdd: Boolean
        get() = preview != null && preview.count > 0 && !checking && previewError == null && !adding
}

/**
 * Adding many links at once (#266): what a list or a pattern names, asked of the API as
 * typing pauses, then every link's answer. The API owns the pattern grammar; this repeats
 * none of it. [onAdded] runs after a batch landed, for the list's counts.
 */
class BatchStore(
    private val api: BatchApi,
    private val scope: CoroutineScope,
    initialList: String = "",
    private val onAdded: suspend () -> Unit = {},
    private val delayMs: Long = BATCH_PREVIEW_DELAY_MS,
) {
    private val mutableState = MutableStateFlow(BatchState(list = initialList))
    val state: StateFlow<BatchState> = mutableState.asStateFlow()

    private var previewing: Job? = null

    init {
        if (initialList.isNotBlank()) schedulePreview()
    }

    fun setKind(kind: BatchKind) {
        if (kind == state.value.kind) return
        mutableState.update { it.copy(kind = kind) }
        schedulePreview()
    }

    fun setText(text: String) {
        mutableState.update { if (it.kind == BatchKind.LIST) it.copy(list = text) else it.copy(pattern = text) }
        schedulePreview()
    }

    /** Back to the form after the results, keeping what was typed. */
    fun startOver() {
        mutableState.update { it.copy(results = null, addError = null) }
    }

    /** True once the batch landed; its results are then in [state]. */
    suspend fun add(preset: String): Boolean {
        val asked = state.value
        if (!asked.canAdd) return false
        mutableState.update { it.copy(adding = true, addError = null) }
        return try {
            val items = api.addBatch(asked.kind, asked.text, preset).map { it.toItem() }
            mutableState.update { it.copy(adding = false, results = items) }
            onAdded()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(adding = false, addError = error.message ?: "Could not add these links") }
            false
        }
    }

    private fun schedulePreview() {
        previewing?.cancel()
        val asked = state.value
        if (asked.text.isBlank()) {
            mutableState.update { it.copy(checking = false, preview = null, previewError = null) }
            return
        }
        mutableState.update { it.copy(checking = true, previewError = null) }
        previewing =
            scope.launch {
                delay(delayMs)
                val answer =
                    try {
                        Result.success(api.previewBatch(asked.kind, asked.text))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                // Dropped if the source changed meanwhile: a cancel can miss a request already out.
                val now = state.value
                if (now.kind != asked.kind || now.text != asked.text) return@launch
                mutableState.update {
                    answer.fold(
                        { preview -> it.copy(checking = false, preview = preview, previewError = null) },
                        { error ->
                            it.copy(checking = false, preview = null, previewError = error.message ?: "Could not check these links")
                        },
                    )
                }
            }
    }
}
