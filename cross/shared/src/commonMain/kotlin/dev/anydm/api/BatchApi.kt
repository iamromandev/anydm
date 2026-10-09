package dev.anydm.api

import dev.anydm.model.BatchItemDto
import dev.anydm.model.BatchKind
import dev.anydm.model.BatchPreview

/** What `BatchStore` asks of the API. `AnydmApi` implements it; tests fake it. */
interface BatchApi {
    /** The links [text] names: a list's lines, trimmed by the API, or a pattern it expands. */
    suspend fun previewBatch(
        kind: BatchKind,
        text: String,
    ): BatchPreview

    /** Adds each link as the add box would; one answer per link, in order. */
    suspend fun addBatch(
        kind: BatchKind,
        text: String,
        preset: String,
    ): List<BatchItemDto>
}
