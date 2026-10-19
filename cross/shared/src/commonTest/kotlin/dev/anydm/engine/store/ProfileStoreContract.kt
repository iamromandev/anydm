package dev.anydm.engine.store

import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.EmbedChoice
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.SponsorBlockChoice
import dev.anydm.engine.core.SponsorBlockMode
import dev.anydm.engine.core.SubtitleChoice
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.files.Profile
import dev.anydm.engine.files.ProfileResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test

/** What every [ProfileStore] must do (SET-02), each test on a new database from [newDriver]. */
abstract class ProfileStoreContract : StoreTestBase() {
    private val folder = Path(SystemTemporaryDirectory, "music").toString()

    private fun new(
        name: String,
        format: Format? = null,
    ) = NewProfile(name, format, null, null, null, null)

    private fun ProfileResult.saved() = shouldBeInstanceOf<ProfileResult.Saved>().profile

    @Test
    fun aProfileRoundTripsWithItsFormat() =
        runTest {
            val profiles = newStore().profiles
            val format =
                Format(
                    audioOnly = AudioFormat.M4A,
                    audioLanguage = "en",
                    embed = EmbedChoice(metadata = true, thumbnail = true, chapters = true),
                )

            val saved = profiles.add(NewProfile("Music", format, null, folder, "{uploader} - {title}", 4096)).saved()

            saved shouldBe Profile(saved.id, "Music", format, null, folder, "{uploader} - {title}", 4096)
            profiles.get(saved.id) shouldBe saved
            profiles.list() shouldBe listOf(saved)
        }

    @Test
    fun aVideoFormatWithSubtitlesAndSponsorBlockRoundTrips() =
        runTest {
            val profiles = newStore().profiles
            val format =
                Format(
                    height = 720,
                    subtitles = SubtitleChoice(listOf("en", "pt-BR"), includeAuto = true),
                    sponsorBlock = SponsorBlockChoice(SponsorBlockMode.CUT, listOf("sponsor")),
                )

            val saved = profiles.add(new("Lectures", format)).saved()

            profiles.get(saved.id)!!.format shouldBe format
        }

    @Test
    fun namesAreUnique() =
        runTest {
            val profiles = newStore().profiles
            val first = profiles.add(new("Music")).saved()
            val other = profiles.add(new("Video")).saved()

            profiles.add(new("Music")) shouldBe ProfileResult.NameTaken
            profiles.update(other.copy(name = "Music")) shouldBe ProfileResult.NameTaken
            profiles.update(first.copy(name = "Music", speedLimit = 10)).saved().speedLimit shouldBe 10
            profiles.list().size shouldBe 2
        }

    @Test
    fun updateOfAMissingProfileIsNotFound() =
        runTest {
            val profiles = newStore().profiles

            profiles.update(Profile(ProfileId(99), "Ghost", null, null, null, null, null)) shouldBe ProfileResult.NotFound
        }

    @Test
    fun listIsOrderedByName() =
        runTest {
            val profiles = newStore().profiles
            profiles.add(new("Video"))
            profiles.add(new("Audio"))
            profiles.add(new("Podcasts"))

            profiles.list().map { it.name } shouldBe listOf("Audio", "Podcasts", "Video")
        }

    @Test
    fun deleteClearsJobsAndTheDefaultAndCountsThem() =
        runTest {
            val store = newStore()
            val profile = store.profiles.add(new("Music")).saved()
            val other = store.profiles.add(new("Video")).saved()
            val a = store.downloads.add(NewDownload(DownloadKind.SITE, profileId = profile.id))
            val b = store.downloads.add(NewDownload(DownloadKind.SITE, profileId = profile.id))
            val c = store.downloads.add(NewDownload(DownloadKind.SITE, profileId = other.id))
            store.settings.update { it.copy(defaultProfileId = profile.id) }

            store.profiles.delete(profile.id) shouldBe 2

            store.profiles.get(profile.id) shouldBe null
            store.downloads.get(a.id)!!.profileId shouldBe null
            store.downloads.get(b.id)!!.profileId shouldBe null
            store.downloads.get(c.id)!!.profileId shouldBe other.id
            store.settings.load().defaultProfileId shouldBe null
        }

    @Test
    fun deleteLeavesAnotherDefaultAlone() =
        runTest {
            val store = newStore()
            val profile = store.profiles.add(new("Music")).saved()
            val other = store.profiles.add(new("Video")).saved()
            store.settings.update { it.copy(defaultProfileId = other.id) }

            store.profiles.delete(profile.id)

            store.settings.load().defaultProfileId shouldBe other.id
        }

    @Test
    fun deleteOfAnUnknownProfileIsZero() =
        runTest {
            newStore().profiles.delete(ProfileId(42)) shouldBe 0
        }

    @Test
    fun anUnreadableFormatLoadsAsNoFormat() =
        runTest {
            val store = newStore()
            val saved = store.profiles.add(new("Music", Format(height = 480))).saved()
            driver.exec("UPDATE profile SET format = 'garbage' WHERE id = ${saved.id.value}")

            store.profiles.get(saved.id)!!.format shouldBe null
        }

    @Test
    fun theDefaultProfileRoundTrips() =
        runTest {
            val store = newStore()
            val saved = store.profiles.add(new("Music")).saved()

            store.settings.load().defaultProfileId shouldBe null
            store.settings.update { it.copy(defaultProfileId = saved.id) }.defaultProfileId shouldBe saved.id
            store.settings.load().defaultProfileId shouldBe saved.id
            store.settings.update { it.copy(defaultProfileId = null) }
            driver.texts("SELECT name FROM setting WHERE name = 'profile.default'") shouldBe emptyList()
        }
}
