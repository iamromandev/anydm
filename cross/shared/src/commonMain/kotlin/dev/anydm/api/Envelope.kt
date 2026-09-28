package dev.anydm.api

import dev.anydm.model.AnydmJson
import dev.anydm.model.PageMetaDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** An answer's `data`, and its `meta` when it's a page. */
data class Envelope(
    val data: JsonElement?,
    val meta: PageMetaDto?,
)

/**
 * The API's answer, unwrapped: `{status, code, data, meta}` or `{status: "error", code, type, message}`,
 * and the older `{success, data | error}`. The same two shapes the web client's `unwrap` reads.
 */
fun unwrap(
    httpStatus: Int,
    body: String,
): Envelope {
    if (httpStatus == 204 || (body.isBlank() && httpStatus in 200..299)) return Envelope(null, null)
    val json =
        runCatching { AnydmJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: fail(httpStatus, "Request failed (HTTP $httpStatus)", null)
    val status = json["status"]?.jsonPrimitive?.contentOrNull
    val success = json["success"]?.jsonPrimitive?.booleanOrNull
    val ok = if (status != null) status == "success" else success == true && httpStatus < 400
    if (ok) {
        val meta = json["meta"]?.let { AnydmJson.decodeFromJsonElement<PageMetaDto>(it) }
        return Envelope(json["data"], meta)
    }
    val message =
        json["message"]?.jsonPrimitive?.contentOrNull
            ?: json["error"]?.jsonPrimitive?.contentOrNull
            ?: "Request failed (HTTP $httpStatus)"
    val code = json["code"]?.jsonPrimitive?.intOrNull ?: httpStatus
    fail(code, message, json["type"]?.jsonPrimitive?.contentOrNull)
}

private fun fail(
    code: Int,
    message: String,
    type: String?,
): Nothing = throw if (code == 401) Unauthorized(message) else ApiException(message, code, type)
