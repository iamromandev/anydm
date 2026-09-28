package dev.anydm.model

import kotlinx.serialization.json.Json

/** How the client reads the API: a field it doesn't know is ignored, never an error. */
val AnydmJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }
