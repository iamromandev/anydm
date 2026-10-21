package dev.anydm.engine.platform

/** Counts acquires and releases; [log] has "acquire" and "release" in order. */
internal class FakeKeepAwake : KeepAwake {
    var acquires = 0
    var releases = 0
    val log = mutableListOf<String>()

    /** True after an acquire with no release since. */
    val held: Boolean get() = log.lastOrNull() == "acquire"

    override suspend fun acquire() {
        acquires++
        log += "acquire"
    }

    override suspend fun release() {
        releases++
        log += "release"
    }
}
