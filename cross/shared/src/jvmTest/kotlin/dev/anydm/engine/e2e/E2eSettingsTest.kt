package dev.anydm.engine.e2e

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.test.Test

/** The e2eTest task hands its settings to the tests. */
class E2eSettingsTest {
    @Test
    fun theCaseCountIsPassedIn() {
        System.getProperty("e2e.cases")?.toIntOrNull().shouldNotBeNull() shouldBeGreaterThanOrEqual 1
    }
}
