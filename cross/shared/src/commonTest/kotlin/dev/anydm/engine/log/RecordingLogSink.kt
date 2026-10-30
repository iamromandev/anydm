package dev.anydm.engine.log

/** Keeps every line it is given, for tests. */
internal class RecordingLogSink : LogSink {
    val lines = mutableListOf<String>()

    override fun write(line: String) {
        lines += line
    }
}
