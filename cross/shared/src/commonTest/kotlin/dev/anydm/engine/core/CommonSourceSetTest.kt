package dev.anydm.engine.core

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

// Wiring canary: proves this source set compiles and runs; replace once real tests live here.
class CommonSourceSetTest {
    @Test
    fun runsKotestPropertiesInCommonCode() =
        runTest {
            checkAll(Arb.int(), Arb.int()) { a, b ->
                a + b shouldBe b + a
            }
        }
}
