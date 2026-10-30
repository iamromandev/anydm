package dev.anydm.desktop

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.CategoryResult
import dev.anydm.engine.files.Profile
import dev.anydm.engine.files.ProfileResult
import dev.anydm.engine.net.ProxyEntry
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.DayTraffic
import dev.anydm.engine.store.HistoryEntry
import dev.anydm.engine.store.ImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/** A category or profile being edited: [id] null for a new one; [startFolder] is its folder when the edit began. */
data class Editing<F, K, E>(
    val id: K?,
    val form: F,
    val errors: Map<E, String> = emptyMap(),
    val startFolder: String? = null,
)

/** The history list shows this many of the newest entries at most; the history itself may keep more. */
const val HISTORY_SHOWN = 200

private const val MAX_SETTINGS_FILE = 4L * 1024 * 1024

/** A question the settings screen waits on. */
sealed interface SettingsConfirm {
    /** The transfer settings have unsaved changes; going to [to] drops them. */
    data class Discard(
        val to: Section,
    ) : SettingsConfirm

    data object ClearHistory : SettingsConfirm

    data object Reset : SettingsConfirm

    data class DeleteCategory(
        val category: Category,
    ) : SettingsConfirm

    data class DeleteProfile(
        val profile: Profile,
    ) : SettingsConfirm
}

/** One day's traffic, all networks and kinds together. */
data class DayTotal(
    val day: String,
    val down: Long,
    val up: Long,
)

data class SettingsState(
    val section: Section = Section.FILES,
    /** False until the settings are read, and when they can't be: the screen is then read-only with [message]. */
    val loaded: Boolean = false,
    val saved: TransferForm = TransferForm(),
    val draft: TransferForm = TransferForm(),
    val errors: Map<SettingField, String> = emptyMap(),
    /** The saved default folder isn't there now (a drive unplugged, say): downloads go to Downloads meanwhile. */
    val folderMissing: Boolean = false,
    val busy: Boolean = false,
    /** The last outcome to tell: saved, refused and why, imported. */
    val message: String? = null,
    val categories: List<Category> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    /** The saved proxies a profile can pick. */
    val proxies: List<ProxyEntry> = emptyList(),
    val defaultProfile: ProfileId? = null,
    val category: Editing<CategoryForm, CategoryId, CategoryField>? = null,
    val profile: Editing<ProfileForm, ProfileId, ProfileField>? = null,
    val history: List<HistoryEntry> = emptyList(),
    val traffic: List<DayTotal> = emptyList(),
    /** Bytes in and out per network over the days [traffic] covers. */
    val networks: Map<String, Pair<Long, Long>> = emptyMap(),
    val confirm: SettingsConfirm? = null,
) {
    val dirty: Boolean get() = draft != saved

    val canSave: Boolean get() = loaded && dirty && errors.isEmpty() && !busy
}

/**
 * The settings screen (#608): the transfer settings as a draft that is saved through the engine, categories, profiles,
 * history and traffic, reset and backup. Quit after the queue is the app's own, in [quitAfterQueue].
 */
class SettingsModel(
    private val source: EngineSource,
    private val scope: CoroutineScope,
    /** Asked on [io] only, at load and save: a slow share must not stall typing. */
    private val folderExists: (String) -> Boolean = { runCatching { Files.isDirectory(Path.of(it)) }.getOrDefault(false) },
    /** Reading and writing backup files, and looking at folders, can block, so never on the UI thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    /** Told when the categories change, so the sidebar and add dialog show them. */
    private val onCategories: (List<Category>) -> Unit = {},
) {
    private val ui = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = ui.asStateFlow()

    /** Quit anydm once the queue is done; kept for this run only. */
    val quitAfterQueue = MutableStateFlow(false)

    // One reorder at a time: each reads the list the last one left.
    private val moving = Mutex()

    /** Reads everything again; an unsaved draft is kept, so coming back to the screen finds it as it was left. */
    fun load() =
        scope.launch {
            guarded {
                val transfer = source.transferSettings()
                val form = transferForm(transfer, quitAfterQueue.value)
                val missing = missing(form.folder)
                ui.update {
                    val keep = it.loaded && it.dirty
                    it.copy(
                        loaded = true,
                        saved = form,
                        draft = if (keep) it.draft else form,
                        errors = if (keep) it.errors else emptyMap(),
                        folderMissing = missing,
                    )
                }
                lists()
                if (ui.value.section == Section.HISTORY) history()
            }
        }

    fun select(section: Section) {
        val current = ui.value
        if (section == current.section) return
        if (current.section.transfer && !section.transfer && current.dirty) {
            ui.update { it.copy(confirm = SettingsConfirm.Discard(section)) }
            return
        }
        go(section)
    }

    private fun go(section: Section) {
        ui.update {
            val leaving = it.section.transfer && !section.transfer
            it.copy(
                section = section,
                draft = if (leaving) it.saved else it.draft,
                errors = if (leaving) emptyMap() else it.errors,
                category = null,
                profile = null,
                message = null,
            )
        }
        if (section == Section.HISTORY) scope.launch { guarded { history() } }
        // Proxies are added in their own section, so the profile editor reads them again when it opens.
        if (section == Section.PROFILES) {
            scope.launch {
                guarded {
                    val proxies = source.proxies()
                    ui.update { it.copy(proxies = proxies) }
                }
            }
        }
    }

    fun edit(change: (TransferForm) -> TransferForm) =
        ui.update {
            val draft = change(it.draft)
            it.copy(draft = draft, errors = draft.validate { true }, message = null)
        }

    fun revert() = ui.update { it.copy(draft = it.saved, errors = emptyMap(), message = null) }

    fun save() {
        var forms: Pair<TransferForm, TransferForm>? = null
        ui.update {
            // The lambda may run again; only its last run counts.
            forms = null
            if (!it.canSave) return@update it
            forms = it.saved to it.draft
            it.copy(busy = true)
        }
        val (saved, draft) = forms ?: return
        scope.launch {
            try {
                guarded {
                    // A folder that is gone is fine while it is the one already saved; a new one must be there.
                    if (draft.folder.trim() != saved.folder.trim() && missing(draft.folder)) {
                        ui.update { it.copy(errors = it.errors + (SettingField.FOLDER to "There is no such folder.")) }
                        return@guarded
                    }
                    when (val result = source.applyTransferSettings(mergeEdits(source.transferSettings(), saved, draft))) {
                        is CommandResult.Done -> {
                            quitAfterQueue.value = draft.afterQueue == AfterQueueChoice.QUIT
                            val now = transferForm(source.transferSettings(), quitAfterQueue.value)
                            val missing = missing(now.folder)
                            // What was typed while it saved stays as a draft over the new values.
                            ui.update {
                                it.copy(
                                    saved = now,
                                    draft =
                                        if (it.draft ==
                                            draft
                                        ) {
                                            now
                                        } else {
                                            it.draft
                                        },
                                    folderMissing = missing,
                                    message = "Saved.",
                                )
                            }
                        }

                        else -> {
                            ui.update { it.copy(message = "Not saved: ${refusal(result)}.") }
                        }
                    }
                }
            } finally {
                ui.update { it.copy(busy = false) }
            }
        }
    }

    // Categories.

    fun newCategory() = ui.update { it.copy(category = Editing(null, CategoryForm()), message = null) }

    fun editCategory(category: Category) =
        ui.update { it.copy(category = Editing(category.id, categoryForm(category), startFolder = category.folder), message = null) }

    fun changeCategory(change: (CategoryForm) -> CategoryForm) =
        ui.update { state ->
            val editing = state.category ?: return@update state
            val form = change(editing.form)
            state.copy(category = editing.copy(form = form, errors = form.validate { true }))
        }

    fun cancelCategory() = ui.update { it.copy(category = null) }

    fun saveCategory() {
        val editing = ui.value.category ?: return
        val errors = editing.form.validate { true }
        if (errors.isNotEmpty()) {
            ui.update { it.copy(category = editing.copy(errors = errors)) }
            return
        }
        scope.launch {
            guarded {
                if (newFolderMissing(editing.form.folder, editing)) {
                    ui.update { it.copy(category = editing.copy(errors = mapOf(CategoryField.FOLDER to "There is no such folder."))) }
                    return@guarded
                }
                val categories = ui.value.categories
                val result =
                    if (editing.id == null) {
                        source.addCategory(editing.form.toNew((categories.maxOfOrNull { it.priority } ?: -1) + 1))
                    } else {
                        val priority = categories.firstOrNull { it.id == editing.id }?.priority ?: 0
                        source.updateCategory(editing.form.toCategory(editing.id, priority))
                    }
                when (result) {
                    is CategoryResult.Saved -> ui.update { it.copy(category = null, message = "Saved.") }
                    CategoryResult.NameTaken -> nameTaken(editing)
                    CategoryResult.NotFound -> ui.update { it.copy(category = null, message = "That category was deleted meanwhile.") }
                }
                lists()
            }
        }
    }

    private fun nameTaken(editing: Editing<CategoryForm, CategoryId, CategoryField>) =
        ui.update { it.copy(category = editing.copy(errors = mapOf(CategoryField.NAME to "Another category has this name."))) }

    fun askDeleteCategory(category: Category) = ui.update { it.copy(confirm = SettingsConfirm.DeleteCategory(category)) }

    /** Moves [category] one place up or down; the order is which category's rules are asked first. */
    fun moveCategory(
        category: Category,
        up: Boolean,
    ) = scope.launch {
        moving.withLock {
            guarded {
                val list = ui.value.categories.toMutableList()
                val at = list.indexOfFirst { it.id == category.id }
                val to = if (up) at - 1 else at + 1
                if (at < 0 || to !in list.indices) return@guarded
                list.add(to, list.removeAt(at))
                list.forEachIndexed { priority, it -> if (it.priority != priority) source.updateCategory(it.copy(priority = priority)) }
                lists()
            }
        }
    }

    // Profiles.

    fun newProfile() = ui.update { it.copy(profile = Editing(null, ProfileForm()), message = null) }

    fun editProfile(profile: Profile) =
        ui.update {
            it.copy(
                profile = Editing(profile.id, profileForm(profile), startFolder = profile.saveFolder.orEmpty()),
                message = null,
            )
        }

    fun changeProfile(change: (ProfileForm) -> ProfileForm) =
        ui.update { state ->
            val editing = state.profile ?: return@update state
            val form = change(editing.form)
            state.copy(profile = editing.copy(form = form, errors = form.validate { true }))
        }

    fun cancelProfile() = ui.update { it.copy(profile = null) }

    fun saveProfile() {
        val editing = ui.value.profile ?: return
        val errors = editing.form.validate { true }
        if (errors.isNotEmpty()) {
            ui.update { it.copy(profile = editing.copy(errors = errors)) }
            return
        }
        scope.launch {
            guarded {
                if (editing.form.folder.isNotBlank() && newFolderMissing(editing.form.folder, editing)) {
                    ui.update { it.copy(profile = editing.copy(errors = mapOf(ProfileField.FOLDER to "There is no such folder."))) }
                    return@guarded
                }
                val result =
                    if (editing.id == null) {
                        source.addProfile(editing.form.toNew())
                    } else {
                        val profile = ui.value.profiles.firstOrNull { it.id == editing.id }
                        if (profile == null) ProfileResult.NotFound else source.updateProfile(editing.form.toProfile(profile))
                    }
                when (result) {
                    is ProfileResult.Saved -> {
                        ui.update { it.copy(profile = null, message = "Saved.") }
                    }

                    ProfileResult.NameTaken -> {
                        ui.update { it.copy(profile = editing.copy(errors = mapOf(ProfileField.NAME to "Another profile has this name."))) }
                    }

                    ProfileResult.NotFound -> {
                        ui.update { it.copy(profile = null, message = "That profile was deleted meanwhile.") }
                    }
                }
                lists()
            }
        }
    }

    fun askDeleteProfile(profile: Profile) = ui.update { it.copy(confirm = SettingsConfirm.DeleteProfile(profile)) }

    fun setDefaultProfile(id: ProfileId?) =
        scope.launch {
            guarded {
                if (!source.setDefaultProfile(id)) ui.update { it.copy(message = "That profile was deleted meanwhile.") }
                lists()
            }
        }

    // History, reset and backup.

    fun askClearHistory() = ui.update { it.copy(confirm = SettingsConfirm.ClearHistory) }

    fun askReset() = ui.update { it.copy(confirm = SettingsConfirm.Reset) }

    fun dismissConfirm() = ui.update { it.copy(confirm = null) }

    /** Does what the open question asks. */
    fun confirm() {
        val asked = ui.value.confirm ?: return
        ui.update { it.copy(confirm = null) }
        when (asked) {
            is SettingsConfirm.Discard -> {
                go(asked.to)
            }

            SettingsConfirm.ClearHistory -> {
                scope.launch {
                    guarded {
                        source.clearHistory()
                        history()
                    }
                }
            }

            SettingsConfirm.Reset -> {
                scope.launch {
                    guarded {
                        when (val result = source.resetTransferSettings()) {
                            // Quit after the queue is an after-queue action, which reset keeps.
                            is CommandResult.Done -> {
                                reloaded("Settings are back to their defaults.")
                            }

                            else -> {
                                ui.update { it.copy(message = "Not reset: ${refusal(result)}.") }
                            }
                        }
                    }
                }
            }

            is SettingsConfirm.DeleteCategory -> {
                scope.launch {
                    guarded {
                        source.deleteCategory(asked.category.id)
                        lists()
                    }
                }
            }

            is SettingsConfirm.DeleteProfile -> {
                scope.launch {
                    guarded {
                        source.deleteProfile(asked.profile.id)
                        lists()
                    }
                }
            }
        }
    }

    /** Writes the settings, categories and profiles to [file]. */
    fun export(file: Path) =
        scope.launch {
            guarded {
                val text = source.exportSettings()
                val failed = withContext(io) { runCatching { Files.writeString(file, text) }.exceptionOrNull() }
                ui.update { it.copy(message = if (failed == null) "Exported to $file." else "Not exported: ${failed.message}.") }
            }
        }

    /** Applies the settings file [file]; one with any fault changes nothing, and the message says what is wrong. */
    fun import(file: Path) =
        scope.launch {
            guarded {
                val read =
                    withContext(io) {
                        runCatching {
                            // A settings file is a few KB; a big one is the wrong file, so it isn't read whole.
                            require(Files.size(file) <= MAX_SETTINGS_FILE) { "that file is too big to be an anydm settings file" }
                            Files.readString(file)
                        }
                    }
                val text =
                    read.getOrElse { e ->
                        ui.update { it.copy(message = "Not imported: ${e.message}.") }
                        return@guarded
                    }
                when (val result = source.importSettings(text)) {
                    ImportResult.Applied -> reloaded("Imported.")
                    is ImportResult.Refused -> ui.update { it.copy(message = "Not imported: " + result.problems.joinToString("; ") + ".") }
                }
            }
        }

    private suspend fun reloaded(message: String) {
        val form = transferForm(source.transferSettings(), quitAfterQueue.value)
        val missing = missing(form.folder)
        ui.update { it.copy(saved = form, draft = form, errors = emptyMap(), folderMissing = missing, message = message) }
        lists()
    }

    private suspend fun lists() {
        val categories = source.categories()
        val profiles = source.profiles()
        val default = source.defaultProfile()
        val proxies = source.proxies()
        ui.update { it.copy(categories = categories, profiles = profiles, proxies = proxies, defaultProfile = default) }
        onCategories(categories)
    }

    private suspend fun history() {
        val limit =
            ui.value.saved.historyLimit
                .toIntOrNull() ?: HISTORY_SHOWN
        val history = source.history(minOf(limit, HISTORY_SHOWN))
        val today = clock.todayIn(zone)
        val days = source.trafficByDay(today.minus(DatePeriod(days = 29)).toString(), today.toString())
        ui.update { it.copy(history = history, traffic = totals(days), networks = networks(days)) }
    }

    private fun totals(days: List<DayTraffic>): List<DayTotal> =
        days
            .groupBy { it.day }
            .map { (day, rows) -> DayTotal(day, rows.sumOf { it.bytes }, rows.sumOf { it.bytesUp }) }
            .sortedByDescending { it.day }

    private fun networks(days: List<DayTraffic>): Map<String, Pair<Long, Long>> =
        days.groupBy { it.network }.mapValues { (_, rows) -> rows.sumOf { it.bytes } to rows.sumOf { it.bytesUp } }

    // [folder] is set and isn't a folder now; looked at on [io].
    private suspend fun missing(folder: String): Boolean = folder.isNotBlank() && !withContext(io) { folderExists(folder.trim()) }

    // A category's or profile's folder, new or changed in this edit, that isn't there. One kept as it was may be gone.
    private suspend fun newFolderMissing(
        folder: String,
        editing: Editing<*, *, *>,
    ): Boolean = (editing.id == null || folder.trim() != editing.startFolder?.trim()) && missing(folder)

    // A stopped engine takes no command: the screen says so instead.
    private suspend fun guarded(block: suspend () -> Unit): Unit? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            ui.update { it.copy(message = "The download engine has stopped, so nothing was changed.") }
            null
        }

    private fun refusal(result: CommandResult): String =
        when (result) {
            is CommandResult.Invalid -> result.reason
            else -> result.toString()
        }
}
