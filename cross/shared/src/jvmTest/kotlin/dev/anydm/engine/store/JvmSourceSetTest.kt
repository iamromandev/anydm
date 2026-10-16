package dev.anydm.engine.store

import io.kotest.matchers.shouldBe
import java.awt.geom.Point2D
import kotlin.test.Test

// Wiring canary: proves this source set compiles and runs; replace once real tests live here.
class JvmSourceSetTest {
    @Test
    fun seesDesktopOnlyJdkApis() {
        Point2D.distance(0.0, 0.0, 3.0, 4.0) shouldBe 5.0
    }
}
