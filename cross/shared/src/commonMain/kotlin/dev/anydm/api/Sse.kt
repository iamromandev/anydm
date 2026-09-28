package dev.anydm.api

/** One server-sent event: its name (`message` when unnamed) and its data lines, joined. */
data class SseFrame(
    val event: String,
    val data: String,
)

/**
 * `text/event-stream`, a line at a time. A frame is emitted at the blank line that ends it;
 * comments (`: ping`) and `id`/`retry` fields are skipped. Pure, so it's tested without a network.
 */
class SseParser {
    private var event = ""
    private val data = StringBuilder()
    private var hasData = false

    fun feed(rawLine: String): SseFrame? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) {
            val frame = if (hasData) SseFrame(event.ifEmpty { "message" }, data.toString()) else null
            event = ""
            data.clear()
            hasData = false
            return frame
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> {
                event = value
            }

            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }
}

/** Every complete frame in [text]; an unfinished last one is left out. */
fun parseSse(text: String): List<SseFrame> {
    val parser = SseParser()
    // What follows the last newline isn't a whole line yet, and not the blank one that ends a frame.
    return text.split('\n').dropLast(1).mapNotNull(parser::feed)
}
