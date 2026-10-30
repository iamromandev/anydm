package dev.anydm.engine.store

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.CategoryRule
import dev.anydm.engine.files.Profile
import dev.anydm.engine.net.ProxyChoice
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class SettingsBackupTest {
    private val videos = Category(CategoryId(1), "Videos", "/v", "{title}", listOf(CategoryRule.Extension(setOf("mkv"))), 0)
    private val music =
        Profile(ProfileId(4), "Music", Format(height = 480), CategoryId(1), "/m", null, 1000, ProxyChoice.Direct)

    @Test
    fun aBackupReadsBackAsItWasWritten() {
        val settings = Settings(clashPolicy = dev.anydm.engine.files.ClashPolicy.SKIP, defaultProfileId = ProfileId(4))
        val file = backupOf(settings, listOf(videos), listOf(music))

        val read = readBackup(file.encode(), emptyList()).shouldBeInstanceOf<BackupRead.Ok>().file

        read shouldBe file
        read.profiles.single().category shouldBe "Videos"
        read.profiles.single().isDefault shouldBe true
    }

    @Test
    fun noProxyOrCookieIsWritten() {
        val text = backupOf(Settings(proxy = ProxyChoice.Direct), listOf(videos), listOf(music)).encode()
        text shouldNotContain "proxy"
        text shouldNotContain "cookie"
    }

    @Test
    fun aBadValueIsNamedAndTheFileRefused() {
        val text =
            BackupFile(
                settings = TransferSettings(),
            ).encode().replace("\"connectionsPerDownload\": 4", "\"connectionsPerDownload\": 99")
        val problems = readBackup(text, emptyList()).shouldBeInstanceOf<BackupRead.Refused>().problems
        problems.single() shouldContain "connectionsPerDownload"
    }

    @Test
    fun repeatedNamesAndMissingCategoriesAreRefused() {
        val file =
            BackupFile(
                categories = listOf(CategoryEntry("A", "/a"), CategoryEntry("a", "/b")),
                profiles = listOf(ProfileEntry("P", category = "Nowhere"), ProfileEntry("P")),
            )
        readBackup(file.encode(), emptyList()).shouldBeInstanceOf<BackupRead.Refused>().problems shouldHaveSize 3
        // A category already on this machine will do.
        val known = BackupFile(profiles = listOf(ProfileEntry("P", category = "Docs")))
        readBackup(known.encode(), listOf("Docs")).shouldBeInstanceOf<BackupRead.Ok>()
    }

    @Test
    fun aBadTemplateOrFolderIsRefused() {
        val file = BackupFile(categories = listOf(CategoryEntry("A", "relative", "{nope}")))
        readBackup(file.encode(), emptyList()).shouldBeInstanceOf<BackupRead.Refused>()
    }

    @Test
    fun aNewerVersionOrNotJsonIsRefused() {
        readBackup(BackupFile(version = 2).encode(), emptyList()).shouldBeInstanceOf<BackupRead.Refused>()
        readBackup("hello", emptyList()).shouldBeInstanceOf<BackupRead.Refused>()
        readBackup(
            """{"settings": {"pacing": {"preset": "CUSTOM", "custom": {"requestSleep": 500, "intervalMin": 0, "intervalMax": 0}}}}""",
            emptyList(),
        ).shouldBeInstanceOf<BackupRead.Refused>()
    }
}
