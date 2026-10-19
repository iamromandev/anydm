package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.Format
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.files.Profile
import dev.anydm.engine.files.ProfileResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import dev.anydm.engine.store.exec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** Profiles read live by the jobs that name them (SET-02), on a new database from [newDriver] for each test. */
abstract class EngineProfileContract : StoreTestBase() {
    private fun TestScope.settle() {
        advanceTimeBy(10.minutes)
        runCurrent()
    }

    private suspend fun TestScope.withEngine(block: suspend (EngineHarness) -> Unit) {
        val h = EngineHarness(this, newStore()).also { it.engine.start() }
        try {
            block(h)
        } finally {
            h.engine.stop()
        }
    }

    private suspend fun EngineHarness.profile(
        format: Format? = null,
        speedLimit: Long? = null,
    ): Profile =
        store.profiles
            .add(NewProfile("Music", format, null, null, null, speedLimit))
            .shouldBeInstanceOf<ProfileResult.Saved>()
            .profile

    private var number = 0

    private suspend fun EngineHarness.job(
        profile: Profile? = null,
        format: Format? = null,
        speedLimit: Long? = null,
    ): DownloadId {
        number++
        return engine
            .add(
                NewDownload(
                    DownloadKind.DIRECT,
                    mirrors = listOf("https://example.com/file-$number.zip"),
                    fileName = "job-$number.zip",
                    profileId = profile?.id,
                    format = format,
                    speedLimit = speedLimit,
                ),
            ).shouldBeInstanceOf<dev.anydm.engine.queue.CommandResult.Done>()
            .download!!
            .id
    }

    private fun EngineHarness.shares(): MutableList<Long?> =
        mutableListOf<Long?>().also { shares -> protocol.onTransfer = { io -> shares += io.speedShare() } }

    @Test
    fun theProbeGetsTheProfilesFormat() =
        runTest {
            withEngine { h ->
                val profile = h.profile(format = Format(height = 480))

                h.job(profile)
                settle()

                h.protocol.requests
                    .first()
                    .format shouldBe Format(height = 480)
            }
        }

    @Test
    fun aJobsFormatBeatsTheProfiles() =
        runTest {
            withEngine { h ->
                val profile = h.profile(format = Format(height = 480))

                h.job(profile, format = Format(height = 720))
                settle()

                h.protocol.requests
                    .first()
                    .format shouldBe Format(height = 720)
            }
        }

    @Test
    fun aJobWithNoProfileAndNoFormatProbesWithNone() =
        runTest {
            withEngine { h ->
                h.job()
                settle()

                h.protocol.requests
                    .first()
                    .format shouldBe null
            }
        }

    @Test
    fun aQueuedJobTakesAProfileEdit() =
        runTest {
            withEngine { h ->
                val shares = h.shares()
                val profile = h.profile(speedLimit = 1000)
                h.engine.pauseAll()
                h.job(profile)
                h.store.profiles.update(profile.copy(speedLimit = 2000))

                h.engine.resumeAll()
                settle()

                shares.single() shouldBe 2000
            }
        }

    @Test
    fun aJobsOwnLimitBeatsTheProfilesLimit() =
        runTest {
            withEngine { h ->
                val shares = h.shares()
                val profile = h.profile(speedLimit = 1000)

                h.job(profile, speedLimit = 500)
                settle()

                shares.single() shouldBe 500
            }
        }

    @Test
    fun aJobPastItsProbeKeepsItsPlanUntilItProbesAgain() =
        runTest {
            withEngine { h ->
                val profile = h.profile(format = Format(height = 480))
                val id = h.job(profile)
                runCurrent()
                h.store.profiles.update(profile.copy(format = Format(height = 1080)))

                h.protocol.requests.size shouldBe 1
                h.engine.pause(id)
                h.engine.resume(id)
                settle()

                h.protocol.requests
                    .last()
                    .format shouldBe Format(height = 1080)
            }
        }

    @Test
    fun deletingTheProfileLeavesTheJobRunnable() =
        runTest {
            withEngine { h ->
                val shares = h.shares()
                val profile = h.profile(speedLimit = 1000)
                h.engine.pauseAll()
                val id = h.job(profile)

                h.store.profiles.delete(profile.id) shouldBe 1
                h.engine.resumeAll()
                settle()

                h.store.downloads
                    .get(id)!!
                    .status shouldBe DownloadStatus.COMPLETED
                shares.single() shouldBe null
            }
        }

    @Test
    fun aNewDownloadTakesTheDefaultProfile() =
        runTest {
            withEngine { h ->
                val profile = h.profile()
                val other =
                    h.store.profiles
                        .add(NewProfile("Other"))
                        .shouldBeInstanceOf<ProfileResult.Saved>()
                        .profile
                h.store.settings.update { it.copy(defaultProfileId = profile.id) }

                val plain = h.job()
                val named = h.job(other)

                h.store.downloads
                    .get(plain)!!
                    .profileId shouldBe profile.id
                h.store.downloads
                    .get(named)!!
                    .profileId shouldBe other.id
            }
        }

    @Test
    fun aDefaultThatPointsNowhereIsIgnored() =
        runTest {
            withEngine { h ->
                val profile = h.profile()
                h.store.settings.update { it.copy(defaultProfileId = profile.id) }
                driver.exec("DELETE FROM profile WHERE id = ${profile.id.value}")

                val id = h.job()

                h.store.downloads
                    .get(id)!!
                    .profileId shouldBe null
            }
        }

    @Test
    fun profilesAreManagedThroughTheEngine() =
        runTest {
            withEngine { h ->
                val saved =
                    h.engine
                        .addProfile(NewProfile("Music", Format(height = 480)))
                        .shouldBeInstanceOf<ProfileResult.Saved>()
                        .profile

                h.engine.profiles() shouldBe listOf(saved)
                h.engine.addProfile(NewProfile("Music")) shouldBe ProfileResult.NameTaken
                h.engine.updateProfile(saved.copy(speedLimit = 5)).shouldBeInstanceOf<ProfileResult.Saved>()
                h.engine
                    .profiles()
                    .single()
                    .speedLimit shouldBe 5
                h.engine.deleteProfile(saved.id) shouldBe 0
                h.engine.profiles() shouldBe emptyList()
            }
        }

    @Test
    fun deletingReportsHowManyJobsUsedTheProfile() =
        runTest {
            withEngine { h ->
                val profile = h.profile()
                h.engine.pauseAll()
                h.job(profile)
                h.job(profile)
                h.job()

                h.engine.deleteProfile(profile.id) shouldBe 2
            }
        }

    @Test
    fun theDefaultProfileCanBeSetAndCleared() =
        runTest {
            withEngine { h ->
                val profile = h.profile()

                h.engine.setDefaultProfile(profile.id) shouldBe true
                h.store.settings
                    .load()
                    .defaultProfileId shouldBe profile.id
                h.engine.setDefaultProfile(null) shouldBe true
                h.store.settings
                    .load()
                    .defaultProfileId shouldBe null
            }
        }

    @Test
    fun aDefaultThatDoesNotExistIsRefused() =
        runTest {
            withEngine { h ->
                h.engine.setDefaultProfile(
                    dev.anydm.engine.core
                        .ProfileId(404),
                ) shouldBe false
                h.store.settings
                    .load()
                    .defaultProfileId shouldBe null
            }
        }
}
