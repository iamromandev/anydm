package dev.anydm.engine.platform

import io.kotest.matchers.shouldBe
import java.util.zip.CRC32
import kotlin.test.Test

// Wiring canary: proves this source set compiles and runs; replace once real tests live here.
class JvmCommonSourceSetTest {
    @Test
    fun seesJvmApisThatAndroidAlsoHas() {
        val crc = CRC32()
        crc.update("123456789".toByteArray())
        crc.value shouldBe 0xCBF43926L
    }
}
