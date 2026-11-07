package dev.anydm.engine

import dev.anydm.engine.http.FakeTransport
import dev.anydm.engine.http.response
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.PublicIpLookup
import dev.anydm.engine.net.Verdict
import dev.anydm.engine.net.currentBoundNetwork
import dev.anydm.engine.platform.FakeNetworkBinder
import dev.anydm.engine.platform.FakeNetworkLister
import dev.anydm.engine.platform.network
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The engine's doctor (#635) checks the networks it lists, and asks the outside server only when `diagnose` is called. */
abstract class EngineDoctorContract : StoreTestBase() {
    private val lookups = mutableListOf<String>()

    private fun doctorTest(body: suspend TestScope.(EngineHarness) -> Unit) =
        runTest {
            val binder = FakeNetworkBinder().also { it.statuses["en5"] = BindStatus.UNSUPPORTED }
            val h =
                EngineHarness(
                    this,
                    newStore(),
                    FakeProtocol(),
                    networkLister = FakeNetworkLister(network("en0"), network("en5")),
                    networkBinder = binder,
                    doctorTransport = FakeTransport { response(204).also { check(currentBoundNetwork() != null) } },
                    publicIp =
                        PublicIpLookup {
                            lookups += it.key
                            "203.0.113.7"
                        },
                )
            try {
                h.engine.start()
                runCurrent()
                body(h)
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun startingTheEngineAsksNoOutsideServer() =
        doctorTest {
            lookups shouldBe emptyList()
        }

    @Test
    fun diagnoseChecksEveryListedNetworkAndLooksUpTheAddressOfTheOnesThatWork() =
        doctorTest { h ->
            val report = h.engine.diagnose()
            report.diagnoses.map { it.network.key to it.verdict } shouldBe listOf("en0" to Verdict.WORKS, "en5" to Verdict.NOT_BINDABLE)
            lookups shouldBe listOf("en0")
        }
}
