package dev.anydm.engine.log

import kotlin.time.Clock
import kotlin.time.Instant

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Where finished lines go (DIA-01). A sink only stores: every line it gets is whole and already redacted. */
interface LogSink {
    /** One line without its newline; an error's trace follows on lines that start with a tab. Called from any thread. */
    fun write(line: String)

    fun close() {}
}

/**
 * The engine's log: `<time> <LEVEL> <tag> <message>`, an error's stack trace after it, all of it through the [Redactor]
 * before the [LogSink] sees it (DIA-02). A message is built only when its level is at least [minLevel]. Logging never
 * throws into its caller.
 */
class Log(
    private val sink: LogSink,
    private val clock: Clock,
    private val redactor: Redactor = Redactor(),
    var minLevel: LogLevel = LogLevel.INFO,
) {
    private var silent = false

    fun debug(
        tag: String,
        message: () -> String,
    ) = log(LogLevel.DEBUG, tag, null, message)

    fun info(
        tag: String,
        message: () -> String,
    ) = log(LogLevel.INFO, tag, null, message)

    fun warn(
        tag: String,
        error: Throwable? = null,
        message: () -> String,
    ) = log(LogLevel.WARN, tag, error, message)

    fun error(
        tag: String,
        error: Throwable? = null,
        message: () -> String,
    ) = log(LogLevel.ERROR, tag, error, message)

    private fun log(
        level: LogLevel,
        tag: String,
        error: Throwable?,
        message: () -> String,
    ) {
        if (silent || level < minLevel) return
        try {
            val text =
                buildString {
                    append(timestamp(clock.now()))
                        .append(' ')
                        .append(level.name.padEnd(5))
                        .append(' ')
                        .append(tag)
                        .append(' ')
                    append(message())
                    error
                        ?.stackTraceToString()
                        ?.trimEnd()
                        ?.lines()
                        ?.forEach { append("\n\t").append(it.trimStart('\t')) }
                }
            sink.write(redactor.redact(text))
        } catch (_: Exception) {
            // A message that throws, or a sink that does, must not break what was being logged.
        }
    }

    companion object {
        /** Drops everything without building a message: the default wherever a log is optional. */
        val None: Log =
            Log(
                sink =
                    object : LogSink {
                        override fun write(line: String) = Unit
                    },
                clock =
                    object : Clock {
                        override fun now() = Instant.fromEpochMilliseconds(0)
                    },
            ).apply { silent = true }

        // ISO-8601 in UTC, always with three digits of milliseconds.
        private fun timestamp(now: Instant): String {
            val text = Instant.fromEpochMilliseconds(now.toEpochMilliseconds()).toString()
            val dot = text.indexOf('.')
            return if (dot < 0) text.removeSuffix("Z") + ".000Z" else text.substring(0, dot + 4) + "Z"
        }
    }
}
