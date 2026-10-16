package dev.anydm.engine.protocol

import dev.anydm.engine.core.aMirror
import dev.anydm.engine.core.aPendingDownload
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ProtocolDefaultsTest {
    @Test
    fun aProtocolThatCantRelinkSaysSo() =
        runTest {
            FakeProtocol().relink(aPendingDownload(), aMirror(), "https://example.com/new") shouldBe
                StepResult.Failed(null, "relink not supported")
        }
}
