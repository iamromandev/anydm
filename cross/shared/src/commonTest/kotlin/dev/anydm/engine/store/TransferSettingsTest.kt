package dev.anydm.engine.store

import dev.anydm.engine.core.Pacing
import dev.anydm.engine.core.PacingPreset
import dev.anydm.engine.core.PacingValues
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.net.AfterQueueAction
import dev.anydm.engine.net.ProxyChoice
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlin.test.Test

class TransferSettingsTest {
    @Test
    fun theDefaultsAreTheEnginesDefaults() {
        Settings().transfer() shouldBe TransferSettings()
        Settings().with(TransferSettings()) shouldBe Settings()
    }

    @Test
    fun withChangesOnlyTheTransferFields() {
        val other = Settings(proxy = ProxyChoice.Direct, clipboardWatch = true, torrentPort = 7000)
        val changed =
            other.with(
                TransferSettings(
                    defaultSaveFolder = "/dl",
                    clashPolicy = ClashPolicy.SKIP,
                    concurrencyLimit = 1,
                    afterQueue = AfterQueueAction.SLEEP,
                ),
            )
        changed.defaultSaveFolder shouldBe Path("/dl")
        changed.clashPolicy shouldBe ClashPolicy.SKIP
        changed.concurrencyLimit shouldBe 1
        changed.afterQueue shouldBe AfterQueueAction.SLEEP
        changed.proxy shouldBe ProxyChoice.Direct
        changed.clipboardWatch shouldBe true
        changed.torrentPort shouldBe 7000
        changed.transfer().defaultSaveFolder shouldBe "/dl"
    }

    @Test
    fun valuesTheEngineRefusesAreProblems() {
        TransferSettings().problems().shouldBeEmpty()
        TransferSettings(connectionsPerDownload = 17).problems().shouldNotBeEmpty()
        TransferSettings(concurrencyLimit = 0).problems().shouldNotBeEmpty()
        TransferSettings(defaultSaveFolder = "relative/dl").problems().shouldNotBeEmpty()
        TransferSettings(globalSpeedLimit = 0).problems().shouldNotBeEmpty()
        TransferSettings(seedRatio = -1.0).problems().shouldNotBeEmpty()
    }

    @Test
    fun itRoundTripsThroughJson() {
        val settings =
            TransferSettings(
                defaultSaveFolder = "/dl",
                pacing = Pacing(PacingPreset.CUSTOM, PacingValues(1.0, 2.0, 3.0)),
                globalSpeedLimit = 1000,
                seedMinutes = 30,
            )
        Json.decodeFromString<TransferSettings>(Json.encodeToString(settings)) shouldBe settings
    }
}
