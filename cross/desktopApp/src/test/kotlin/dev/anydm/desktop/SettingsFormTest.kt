package dev.anydm.desktop

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.Pacing
import dev.anydm.engine.core.PacingPreset
import dev.anydm.engine.core.PacingValues
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.CategoryRule
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.files.Profile
import dev.anydm.engine.net.AfterQueueAction
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.store.TransferSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsFormTest {
    private fun errors(form: TransferForm) = form.validate { it == "/dl" }

    @Test
    fun `the defaults read as the engine's defaults and have no errors`() {
        val form = transferForm(TransferSettings())
        assertEquals("", form.folder)
        assertEquals(ClashPolicy.RENAME, form.clash)
        assertEquals("64", form.reserveMb)
        assertEquals("4", form.connections)
        assertEquals("", form.downKbps)
        assertEquals("3", form.concurrency)
        assertEquals(AfterQueueChoice.NOTHING, form.afterQueue)
        assertEquals(emptyMap(), errors(form))
        assertEquals(TransferSettings(), form.toSettings())
    }

    @Test
    fun `units are megabytes for the reserve and kilobytes a second for speeds`() {
        val settings =
            TransferForm(downKbps = "500", upKbps = "", slowKbps = "128", reserveMb = "1").toSettings()
        assertEquals(500L * 1024, settings.globalSpeedLimit)
        assertNull(settings.globalUploadLimit)
        assertEquals(128L * 1024, settings.slowModeLimit)
        assertEquals(1024L * 1024, settings.freeSpaceReserve)
        assertEquals("500", transferForm(settings).downKbps)
    }

    @Test
    fun `each number outside the engine's limits is named`() {
        val bad =
            TransferForm(
                folder = "/nowhere",
                reserveMb = "-1",
                retries = "x",
                historyLimit = "0",
                connections = "17",
                downKbps = "0",
                slowKbps = "",
                seedRatio = "0",
                seedMinutes = "1.5",
                concurrency = "0",
            )
        assertEquals(
            setOf(
                SettingField.FOLDER,
                SettingField.RESERVE,
                SettingField.RETRIES,
                SettingField.HISTORY,
                SettingField.CONNECTIONS,
                SettingField.DOWN_LIMIT,
                SettingField.SLOW_LIMIT,
                SettingField.SEED_RATIO,
                SettingField.SEED_MINUTES,
                SettingField.CONCURRENCY,
            ),
            errors(bad).keys,
        )
        assertEquals(emptyMap(), errors(TransferForm(folder = "/dl", seedRatio = "1.5", seedMinutes = "90")))
    }

    @Test
    fun `custom pacing must be seconds in range, the least no more than the most`() {
        val custom = TransferForm(pacing = PacingPreset.CUSTOM, pacingSleep = "1", pacingMin = "5", pacingMax = "2")
        assertTrue(SettingField.PACING in errors(custom))
        assertTrue(SettingField.PACING in errors(custom.copy(pacingMax = "500")))
        val good = custom.copy(pacingMax = "10")
        assertEquals(emptyMap(), errors(good))
        assertEquals(Pacing(PacingPreset.CUSTOM, PacingValues(1.0, 5.0, 10.0)), good.toSettings().pacing)
        // Other presets ignore the custom fields.
        assertEquals(emptyMap(), errors(custom.copy(pacing = PacingPreset.BALANCED)))
    }

    @Test
    fun `quit after the queue is the app's own and stores nothing`() {
        val form = transferForm(TransferSettings(afterQueue = AfterQueueAction.SLEEP), quit = false)
        assertEquals(AfterQueueChoice.SLEEP, form.afterQueue)
        assertEquals(AfterQueueChoice.QUIT, transferForm(TransferSettings(), quit = true).afterQueue)
        assertEquals(AfterQueueAction.NONE, form.copy(afterQueue = AfterQueueChoice.QUIT).toSettings().afterQueue)
    }

    @Test
    fun `a category form checks name, folder and template, listing the fields`() {
        val form = CategoryForm(name = " ", folder = "videos", template = "{nope}")
        val errors = form.validate { false }
        assertEquals(setOf(CategoryField.NAME, CategoryField.FOLDER, CategoryField.TEMPLATE), errors.keys)
        assertTrue(errors.getValue(CategoryField.TEMPLATE).contains("{title}"))
    }

    @Test
    fun `a category reads into a form and back`() {
        val category =
            Category(
                CategoryId(3),
                "Videos",
                "/v",
                "{title}",
                listOf(
                    CategoryRule.Extension(setOf("mkv", "mp4")),
                    CategoryRule.Domain(setOf("example.com")),
                    CategoryRule.Kind(setOf(DownloadKind.SITE)),
                ),
                2,
            )
        val form = categoryForm(category)
        assertEquals("mkv, mp4", form.extensions)
        assertEquals(emptyMap(), form.validate { true })
        assertEquals(category, form.toCategory(category.id, category.priority))
        assertEquals(emptyList(), CategoryForm(name = "A", folder = "/a").toNew(0).rules)
    }

    @Test
    fun `a profile keeps its proxy and the parts of its format the form doesn't show`() {
        val profile =
            Profile(ProfileId(1), "Clips", Format(height = 720, fps = 30), null, null, null, 2048, ProxyChoice.Direct)
        val form = profileForm(profile)
        assertEquals(ProfileMedia.P720, form.media)
        assertEquals("2", form.speedKbps)
        val back = form.copy(media = ProfileMedia.P1080).toProfile(profile)
        assertEquals(Format(height = 1080, fps = 30), back.format)
        assertEquals(ProxyChoice.Direct, back.proxy)
        assertNull(form.copy(media = ProfileMedia.ANY).toProfile(profile).format)
        assertTrue(ProfileField.NAME in ProfileForm(name = "a\tb").validate { true })
    }

    @Test
    fun `only the fields the user changed are taken over what the engine has now`() {
        val saved = transferForm(TransferSettings(globalSpeedLimit = 1500))
        val draft = saved.copy(retries = "2")
        // Paused from the toolbar meanwhile, and a limit the form shows rounded.
        val fresh = TransferSettings(globalSpeedLimit = 1500, globalPause = true, afterQueue = AfterQueueAction.NONE)

        val merged = mergeEdits(fresh, saved, draft)

        assertEquals(2, merged.maxRetries)
        assertEquals(true, merged.globalPause)
        assertEquals(1500L, merged.globalSpeedLimit)
    }

    @Test
    fun `numbers too big for the engine and non-numbers are refused`() {
        assertTrue(SettingField.CONCURRENCY in errors(TransferForm(concurrency = "4294967297")))
        assertTrue(SettingField.RETRIES in errors(TransferForm(retries = "99999999999")))
        assertTrue(SettingField.DOWN_LIMIT in errors(TransferForm(downKbps = "99999999999999999")))
        assertTrue(SettingField.PACING in errors(TransferForm(pacing = PacingPreset.CUSTOM, pacingSleep = "NaN")))
    }

    @Test
    fun `a path the system can't take is an error, not a crash`() {
        assertTrue(SettingField.FOLDER in errors(TransferForm(folder = "/a\u0000b")))
        assertTrue(CategoryField.FOLDER in CategoryForm(name = "A", folder = "/a\u0000b").validate { true })
    }

    @Test
    fun `custom pacing values are kept while another preset is chosen`() {
        val form = transferForm(TransferSettings(pacing = Pacing(PacingPreset.CUSTOM, PacingValues(1.0, 2.0, 3.0))))
        assertEquals(
            PacingValues(1.0, 2.0, 3.0),
            form
                .copy(pacing = PacingPreset.BALANCED)
                .toSettings()
                .pacing.custom,
        )
    }

    @Test
    fun `a profile's limit the form shows rounded is kept when not touched`() {
        val profile = Profile(ProfileId(1), "Clips", null, null, null, null, 1500)
        assertEquals(1500L, profileForm(profile).toProfile(profile).speedLimit)
        assertEquals(3072L, profileForm(profile).copy(speedKbps = "3").toProfile(profile).speedLimit)
    }

    @Test
    fun `a limit under a kilobyte a second shows as one, so it never blocks a save`() {
        val form = transferForm(TransferSettings(globalSpeedLimit = 500, slowModeLimit = 500))
        assertEquals("1", form.downKbps)
        assertEquals("1", form.slowKbps)
        assertEquals(emptyMap(), errors(form.copy(retries = "2")))
        // Untouched, the engine's own value stays.
        assertEquals(
            500L,
            mergeEdits(TransferSettings(globalSpeedLimit = 500, slowModeLimit = 500), form, form.copy(retries = "2")).globalSpeedLimit,
        )
        val profile = Profile(ProfileId(1), "Clips", null, null, null, null, 500)
        assertEquals("1", profileForm(profile).speedKbps)
        assertEquals(500L, profileForm(profile).toProfile(profile).speedLimit)
    }
}
