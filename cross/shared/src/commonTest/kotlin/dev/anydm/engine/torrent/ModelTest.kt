package dev.anydm.engine.torrent

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelTest {
    @Test
    fun theBufferCapIsAtLeastOnePieceSoAPieceCanAlwaysBeAssembled() {
        val mib = 1024L * 1024
        assertEquals(MAX_BUFFERED.toLong(), bufferCap(16 * 1024), "small pieces: the usual cap")
        assertEquals(MAX_BUFFERED.toLong(), bufferCap(MAX_BUFFERED.toLong()), "a piece of exactly the cap")
        assertEquals(32 * mib, bufferCap(32 * mib), "bigger pieces raise it to one piece")
        assertEquals(64 * mib, bufferCap(64 * mib), "the largest accepted piece")
        assertEquals(MAX_BUFFERED.toLong(), bufferCap(0), "no piece length")
        assertEquals(MAX_BUFFERED.toLong(), bufferCap(-5))
    }

    @Test
    fun theStallRulesHaveTheirAgreedValues() {
        assertEquals(60_000L, REQUEST_TIMEOUT_MS)
        assertEquals(120_000L, SNUB_MS)
    }
}
