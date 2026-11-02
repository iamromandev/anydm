package dev.anydm.desktop

import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.net.Allowance
import dev.anydm.engine.net.AllowanceAction
import dev.anydm.engine.net.AllowancePeriod
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.net.RuleAction
import dev.anydm.engine.net.ScheduleRule
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.TransferSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DayOfWeek
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/** How often a data limit starts again; the dialog's Day, Week and Month. */
enum class DataPeriod(
    val label: String,
    /** How usage in the period is said: "1 GB of 5 GB this month". */
    val phrase: String,
) {
    DAY("Day", "today"),
    WEEK("Week", "this week"),
    MONTH("Month", "this month"),
}

/** The number fields and the schedule a message can be about. */
enum class LimitField { TOTAL, SLOW, SPEED, DATA, SCHEDULE }

/** Why a draft can't be saved: on [page] (a network key, null for All downloads). */
data class FieldError(
    val page: String?,
    val field: LimitField,
    val message: String,
)

/** What the dialog edits for one network: a speed limit and a data limit, each kept as typed while it is switched off. */
data class NetworkDraft(
    val speedOn: Boolean = false,
    val speedText: String = "",
    val dataOn: Boolean = false,
    val dataText: String = "",
    val period: DataPeriod = DataPeriod.MONTH,
    /** What the saved data limit does at the limit; the dialog has no choice for it, a saved SLOW stays. */
    val atLimit: AllowanceAction = AllowanceAction.PAUSE,
)

/** What the dialog edits for all downloads together. */
data class GeneralDraft(
    val totalOn: Boolean,
    val totalText: String,
    val slowOn: Boolean,
    val slowText: String,
    val atOnce: Int,
    val schedule: DownloadSchedule,
)

data class LimitsDraft(
    val general: GeneralDraft,
    /** The networks the person has looked at or changed, and those the engine listed when the dialog opened. */
    val networks: Map<String, NetworkDraft>,
)

/** How much a network used, and over what. */
data class NetworkUsage(
    val bytes: Long,
    val period: DataPeriod,
)

data class LimitsState(
    val loading: Boolean = true,
    /** Why the settings could not be read: the dialog shows it in place of the pages. */
    val failure: String? = null,
    /** The network shown, by key; null for All downloads. */
    val page: String? = null,
    val unit: SpeedUnit = SpeedUnit.BYTES,
    val draft: LimitsDraft = LimitsDraft(DEFAULT_GENERAL_DRAFT, emptyMap()),
    /** The draft differs from what the engine has. */
    val dirty: Boolean = false,
    val errors: List<FieldError> = emptyList(),
    val usage: Map<String, NetworkUsage> = emptyMap(),
    /** Networks with a data limit the engine has: only those have usage to reset. */
    val resettable: Set<String> = emptySet(),
    /** Pause rules the engine has that no schedule of this dialog made, in words; turning the schedule on replaces them. */
    val foreignRules: List<String> = emptyList(),
    val running: Int = 0,
    val saving: Boolean = false,
    val message: String? = null,
    /** Saved or cancelled: the dialog goes away. */
    val done: Boolean = false,
) {
    val canSave: Boolean get() = !loading && failure == null && dirty && errors.isEmpty() && !saving

    val foreignSchedule: Boolean get() = foreignRules.isNotEmpty()

    /** Saving would delete the pause rules set up elsewhere: the schedule is on over them. */
    val replacesForeign: Boolean get() = foreignRules.isNotEmpty() && draft.general.schedule.enabled

    fun errorOf(
        page: String?,
        field: LimitField,
    ): String? = errors.firstOrNull { it.page == page && it.field == field }?.message
}

private val DEFAULT_GENERAL_DRAFT = GeneralDraft(false, "", false, "", 1, DownloadSchedule())

private val DEFAULT_SLOW_LIMIT = TransferSettings().slowModeLimit

internal const val MIB = 1_048_576L
internal const val GIB = 1_073_741_824L

/** Bytes per second in one unit of what the speed fields are typed in. */
internal val SpeedUnit.unitBytes: Long get() = if (this == SpeedUnit.BYTES) MIB else 125_000L

internal val SpeedUnit.fieldLabel: String get() = if (this == SpeedUnit.BYTES) "MB/s" else "Mbps"

/** The most downloads at once the stepper goes to (a higher number the engine already has stays until it is changed). */
const val MAX_AT_ONCE = 4
const val MIN_AT_ONCE = 1

private val AMOUNT = Regex("^(\\d+(\\.\\d*)?|\\.\\d+)$")

/** A positive plain decimal ("5", "0.5", "1,5"); null for anything else (empty, zero, a sign, an exponent, a unit). */
internal fun parseAmount(text: String): Double? {
    val clean = text.trim().replace(',', '.')
    if (!AMOUNT.matches(clean)) return null
    return clean.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
}

/** [text] in units of [unitBytes] as whole bytes; null when it is no number above zero, or would be below one byte or past what fits. */
internal fun amountBytes(
    text: String,
    unitBytes: Long,
): Long? {
    val value = parseAmount(text) ?: return null
    val bytes = value * unitBytes
    if (bytes < 0.5 || bytes > 9.0e15) return null
    return Math.round(bytes).takeIf { it >= 1 }
}

/** [bytes] in units of [unitBytes] as a short number: two decimals from one up, three significant digits below. */
internal fun amountText(
    bytes: Long,
    unitBytes: Long,
): String {
    val value = BigDecimal(bytes).divide(BigDecimal(unitBytes), MathContext.DECIMAL64)
    val rounded =
        if (value >= BigDecimal.ONE) value.setScale(2, RoundingMode.HALF_UP) else value.round(MathContext(3, RoundingMode.HALF_UP))
    return rounded.stripTrailingZeros().toPlainString()
}

/**
 * What [text] comes to in units of [unitBytes]: one of the [exact] values when the text is what that value reads as (so a value
 * that does not come out whole, 1,048,576 B/s as "8.39" Mbps, is not drifted by reading its own text back), else [amountBytes].
 */
internal fun readAmount(
    text: String,
    unitBytes: Long,
    vararg exact: Long?,
): Long? {
    val typed = text.trim().replace(',', '.')
    return exact.firstOrNull { it != null && amountText(it, unitBytes) == typed } ?: amountBytes(text, unitBytes)
}

/** Whether [draft] is All downloads as a fresh install has it: no limits, slow mode off and at its speed, no schedule. */
internal fun generalAtDefaults(
    draft: GeneralDraft,
    unit: SpeedUnit,
): Boolean {
    val fresh = TransferSettings()
    return !draft.totalOn && !draft.slowOn && draft.atOnce == fresh.concurrencyLimit && !draft.schedule.enabled &&
        readAmount(draft.slowText, unit.unitBytes, fresh.slowModeLimit) == fresh.slowModeLimit
}

/** What a field comes to: switched off, a number of bytes, or a text that is no number. */
private sealed interface Amount {
    data object Off : Amount

    data class Bytes(
        val bytes: Long,
    ) : Amount

    data object Bad : Amount
}

/**
 * The Limits dialog's draft. It reads the engine's transfer settings, network rules, schedule rules and usage when it opens,
 * edits a copy, and writes only what changed on [save]; [cancel] throws the copy away. Resetting a network's data usage is an
 * action rather than a setting, so it happens when asked. Numbers are typed in [unit], the unit of the footer.
 *
 * The part of the transfer settings written is only the fields the person changed (through `updateTransfer`), so the footer's
 * Slow mode switch used meanwhile is not written back over.
 */
class LimitsModel(
    private val source: EngineSource,
    private val unit: SpeedUnit,
    private val scope: CoroutineScope,
    initialPage: String? = null,
    private val pollMillis: Long = USAGE_POLL_MS,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private class Baseline(
        val transfer: TransferSettings,
        val rules: NetworkRules,
        val scheduleRules: List<ScheduleRule>,
    ) {
        val schedule: ScheduleReading = decodeSchedule(scheduleRules)
    }

    private val mutable = MutableStateFlow(LimitsState(page = initialPage, unit = unit))
    val state: StateFlow<LimitsState> = mutable.asStateFlow()

    // Only read and written under the state's own updates and on the one coroutine that loads and saves.
    @Volatile
    private var baseline: Baseline? = null

    init {
        scope.launch { load() }
        if (pollMillis > 0) {
            scope.launch {
                while (isActive) {
                    delay(pollMillis)
                    refreshUsage()
                }
            }
        }
        scope.launch {
            // How many are downloading, for the line under "All downloads".
            source.downloads.collect { list ->
                val running = list.count { it.download.status == DownloadStatus.DOWNLOADING }
                mutable.update { it.copy(running = running) }
            }
        }
    }

    private suspend fun load() {
        try {
            val base = read()
            baseline = base
            val keys =
                source.networks.value.networks
                    .map { it.info.key } + base.rules.networks.keys
            val networks = keys.distinct().associateWith { networkDraftOf(base, it) }
            val draft = LimitsDraft(generalDraftOf(base), networks)
            mutable.update {
                judge(it.copy(loading = false, failure = null, draft = draft, foreignRules = foreignRulesOf(base)))
            }
            refreshUsage()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(loading = false, failure = "The limits could not be read: ${e.message ?: "the engine has stopped."}") }
        }
    }

    private suspend fun read(): Baseline = Baseline(source.transferSettings(), source.networkRules(), source.scheduleRules())

    // The pause rules no schedule of this dialog made, in words; none when they are exactly a schedule's (or there are none).
    private fun foreignRulesOf(base: Baseline): List<String> =
        if (base.schedule == ScheduleReading.Foreign) {
            base.scheduleRules.filter { it.action == RuleAction.PAUSE }.map(::describeRule)
        } else {
            emptyList()
        }

    /** Reads the usage of every network again (the engine's period usage, else this month's traffic). */
    suspend fun refreshUsage() {
        try {
            val base = baseline
            val status = source.allowanceUsage()
            val day = today()
            val first = day.withDayOfMonth(1)
            val monthly =
                source
                    .trafficByDay(first.toString(), day.withDayOfMonth(day.lengthOfMonth()).toString())
                    .groupBy { it.network }
                    .mapValues { (_, days) -> days.sumOf { it.bytes + it.bytesUp } }
            val keys = status.keys + monthly.keys + mutable.value.draft.networks.keys
            val usage =
                keys.associateWith { key ->
                    val own = status[key]
                    if (own != null) {
                        NetworkUsage(own.used, periodOf(base?.rules?.policyFor(key)?.allowance))
                    } else {
                        NetworkUsage(monthly[key] ?: 0L, DataPeriod.MONTH)
                    }
                }
            mutable.update { it.copy(usage = usage, resettable = status.keys) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A stopped engine or a failed read keeps what was shown.
        }
    }

    // Drafts

    private fun generalDraftOf(base: Baseline): GeneralDraft {
        val t = base.transfer
        val schedule = (base.schedule as? ScheduleReading.Window)?.schedule ?: DownloadSchedule()
        return GeneralDraft(
            totalOn = t.globalSpeedLimit != null,
            totalText = amountText(t.globalSpeedLimit ?: (20 * MIB), unit.unitBytes),
            slowOn = t.slowMode,
            slowText = amountText(t.slowModeLimit, unit.unitBytes),
            atOnce = t.concurrencyLimit,
            schedule = schedule,
        )
    }

    private fun networkDraftOf(
        base: Baseline,
        key: String,
    ): NetworkDraft {
        val policy = base.rules.policyFor(key)
        val allowance = policy.allowance
        return NetworkDraft(
            speedOn = policy.speedLimit != null,
            speedText = amountText(policy.speedLimit ?: (10 * MIB), unit.unitBytes),
            dataOn = allowance != null,
            dataText = amountText(allowance?.bytes ?: (5 * GIB), GIB),
            period = periodOf(allowance),
            atLimit = allowance?.atLimit ?: AllowanceAction.PAUSE,
        )
    }

    private fun periodOf(allowance: Allowance?): DataPeriod =
        when (allowance?.period) {
            AllowancePeriod.Daily -> DataPeriod.DAY
            is AllowancePeriod.Weekly -> DataPeriod.WEEK
            else -> DataPeriod.MONTH
        }

    // Edits

    fun select(page: String?) = mutable.update { it.copy(page = page) }

    private fun editGeneral(change: (GeneralDraft) -> GeneralDraft) =
        mutable.update { judge(it.copy(draft = it.draft.copy(general = change(it.draft.general)), message = null)) }

    private fun editNetwork(
        key: String,
        change: (NetworkDraft) -> NetworkDraft,
    ) = mutable.update { now ->
        val current = now.draft.networks[key] ?: baseline?.let { networkDraftOf(it, key) } ?: NetworkDraft()
        judge(now.copy(draft = now.draft.copy(networks = now.draft.networks + (key to change(current))), message = null))
    }

    /** What is typed in a number field: digits and one decimal mark, nothing longer than a person would type. */
    private fun typed(text: String) = text.filter { it.isDigit() || it == '.' || it == ',' }.take(MAX_TYPED)

    fun setTotalLimited(on: Boolean) = editGeneral { it.copy(totalOn = on) }

    fun setTotalText(text: String) = editGeneral { it.copy(totalText = typed(text)) }

    fun setSlowOn(on: Boolean) = editGeneral { it.copy(slowOn = on) }

    fun setSlowText(text: String) = editGeneral { it.copy(slowText = typed(text)) }

    fun stepAtOnce(delta: Int) =
        editGeneral {
            // A number above the stepper's top (set elsewhere) can come down but not go up.
            val top = maxOf(MAX_AT_ONCE, it.atOnce)
            it.copy(atOnce = (it.atOnce + delta).coerceIn(MIN_AT_ONCE, top))
        }

    fun setScheduleEnabled(on: Boolean) = editGeneral { it.copy(schedule = it.schedule.copy(enabled = on)) }

    fun setScheduleStart(minute: Int) = editGeneral { it.copy(schedule = it.schedule.copy(startMinute = minute)) }

    fun setScheduleEnd(minute: Int) = editGeneral { it.copy(schedule = it.schedule.copy(endMinute = minute)) }

    fun toggleScheduleDay(day: DayOfWeek) =
        editGeneral {
            val days = if (day in it.schedule.days) it.schedule.days - day else it.schedule.days + day
            it.copy(schedule = it.schedule.copy(days = days))
        }

    /** All downloads as a fresh install has them (in the draft; Save writes it). The network limits stay as they are. */
    fun resetGeneral() {
        val fresh = TransferSettings()
        editGeneral {
            GeneralDraft(
                totalOn = false,
                totalText = amountText(20 * MIB, unit.unitBytes),
                slowOn = false,
                slowText = amountText(fresh.slowModeLimit, unit.unitBytes),
                atOnce = fresh.concurrencyLimit,
                schedule = DownloadSchedule(),
            )
        }
    }

    /** Whether All downloads already is as a fresh install has it (the Reset button is off then). */
    fun generalIsDefault(): Boolean = generalAtDefaults(mutable.value.draft.general, unit)

    fun setSpeedLimited(
        key: String,
        on: Boolean,
    ) = editNetwork(key) { it.copy(speedOn = on) }

    fun setSpeedText(
        key: String,
        text: String,
    ) = editNetwork(key) { it.copy(speedText = typed(text)) }

    fun setDataLimited(
        key: String,
        on: Boolean,
    ) = editNetwork(key) { it.copy(dataOn = on) }

    fun setDataText(
        key: String,
        text: String,
    ) = editNetwork(key) { it.copy(dataText = typed(text)) }

    fun setPeriod(
        key: String,
        period: DataPeriod,
    ) = editNetwork(key) { it.copy(period = period) }

    /** A network without a speed or data limit (in the draft; Save writes it). */
    fun removeLimits(key: String) = editNetwork(key) { it.copy(speedOn = false, dataOn = false) }

    fun hasLimits(key: String): Boolean =
        mutable.value.draft.networks[key]
            ?.let { it.speedOn || it.dataOn } ?: false

    /** Starts the data usage of [key] over now (not part of the draft). The answer is [LimitsState.message] when it can't. */
    suspend fun resetUsage(key: String) {
        try {
            val result = withContext(NonCancellable) { source.resetAllowanceUsage(key) }
            if (result is CommandResult.Done) {
                mutable.update { it.copy(message = null) }
                refreshUsage()
            } else {
                mutable.update { it.copy(message = "Data usage could not be reset: ${reason(result)}") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            mutable.update { it.copy(message = "Data usage could not be reset: the engine has stopped.") }
        } catch (e: Exception) {
            mutable.update { it.copy(message = "Data usage could not be reset: ${e.message ?: e::class.simpleName ?: "an unknown error"}") }
        }
    }

    fun dismissMessage() = mutable.update { it.copy(message = null) }

    fun cancel() = mutable.update { it.copy(done = true) }

    // Judging a draft

    private fun amountOf(
        on: Boolean,
        text: String,
        unitBytes: Long,
        vararg exact: Long?,
    ): Amount {
        if (!on) return Amount.Off
        // A value the person did not touch is the engine's own (or the default's), not what its rounded text makes of it.
        return readAmount(text, unitBytes, *exact)?.let(Amount::Bytes) ?: Amount.Bad
    }

    private fun speedAmount(
        on: Boolean,
        text: String,
        vararg exact: Long?,
    ) = amountOf(on, text, unit.unitBytes, *exact)

    private fun baseAmount(bytes: Long?): Amount = bytes?.let(Amount::Bytes) ?: Amount.Off

    private fun judge(now: LimitsState): LimitsState {
        val base = baseline ?: return now
        val errors = mutableListOf<FieldError>()
        val g = now.draft.general
        val total = speedAmount(g.totalOn, g.totalText, base.transfer.globalSpeedLimit)
        val slow = speedAmount(true, g.slowText, base.transfer.slowModeLimit, DEFAULT_SLOW_LIMIT)
        if (total == Amount.Bad) errors += FieldError(null, LimitField.TOTAL, "Enter a speed above 0.")
        if (slow == Amount.Bad) errors += FieldError(null, LimitField.SLOW, "Enter a speed above 0.")
        if (g.schedule.enabled && !g.schedule.valid) {
            errors += FieldError(null, LimitField.SCHEDULE, scheduleProblem(g.schedule))
        }
        var dirty =
            total != baseAmount(base.transfer.globalSpeedLimit) ||
                slow != baseAmount(base.transfer.slowModeLimit) ||
                g.slowOn != base.transfer.slowMode ||
                g.atOnce != base.transfer.concurrencyLimit ||
                scheduleChanged(g.schedule, base)
        for ((key, n) in now.draft.networks) {
            val policy = base.rules.policyFor(key)
            val speed = speedAmount(n.speedOn, n.speedText, policy.speedLimit)
            val data = amountOf(n.dataOn, n.dataText, GIB, policy.allowance?.bytes)
            if (speed == Amount.Bad) errors += FieldError(key, LimitField.SPEED, "Enter a speed above 0.")
            if (data == Amount.Bad) errors += FieldError(key, LimitField.DATA, "Enter an amount above 0.")
            val periodChanged = n.dataOn && n.period != periodOf(policy.allowance)
            if (speed != baseAmount(policy.speedLimit) || data != baseAmount(policy.allowance?.bytes) || periodChanged) dirty = true
        }
        return now.copy(dirty = dirty, errors = errors)
    }

    private fun scheduleProblem(schedule: DownloadSchedule) =
        when {
            !isTimeOfDay(schedule.startMinute) || !isTimeOfDay(schedule.endMinute) -> {
                "Enter the start and stop times as hours and minutes."
            }

            schedule.days.isEmpty() -> {
                "Pick at least one day for the schedule."
            }

            else -> {
                "The schedule must start and stop at different times."
            }
        }

    // Whether the draft's schedule is not what the engine's rules hold. A draft that is off equals no schedule (Off) and is
    // untouched when the engine has foreign rules (they stay).
    private fun scheduleChanged(
        draft: DownloadSchedule,
        base: Baseline,
    ): Boolean =
        when (val reading = base.schedule) {
            ScheduleReading.Off, ScheduleReading.Foreign -> draft.enabled
            is ScheduleReading.Window -> if (draft.enabled) draft != reading.schedule else true
        }

    // Saving

    /**
     * Writes what changed: network policies, then the transfer settings, then the schedule. Done when all of it was taken.
     * A schedule turned on over pause rules set up elsewhere deletes them ([LimitsState.replacesForeign]): that is written only
     * when [replaceForeign] says the person has been asked.
     */
    suspend fun save(replaceForeign: Boolean = false) {
        val now = mutable.value
        if (!now.canSave) return
        if (now.replacesForeign && !replaceForeign) {
            mutable.update { it.copy(message = "Not saved: the pause rules set up elsewhere are replaced only once that is confirmed.") }
            return
        }
        mutable.update { it.copy(saving = true, message = null) }
        var failure: Failure? = null
        try {
            withContext(NonCancellable) { failure = write(now.draft) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            failure = Failure("the engine has stopped.")
        } catch (e: Exception) {
            failure = Failure(e.message ?: e::class.simpleName ?: "an unknown error")
        }
        // Whatever was taken is the new base; what was not stays in the draft as a change.
        val fresh = runCatching { read() }.getOrNull()
        if (fresh != null) baseline = fresh
        mutable.update {
            val next = judge(it.copy(saving = false, foreignRules = fresh?.let(::foreignRulesOf) ?: it.foreignRules))
            val failed = failure
            when {
                failed == null -> next.copy(done = true)
                failed.saved -> next.copy(message = failed.text)
                else -> next.copy(message = "Not saved: ${failed.text}")
            }
        }
        refreshUsage()
    }

    // Why something was not written; [saved] when the change itself went in and only a clean-up did not (then [text] is the whole message).
    private class Failure(
        val text: String,
        val saved: Boolean = false,
    )

    // Returns why something was not written, null when everything was.
    private suspend fun write(draft: LimitsDraft): Failure? {
        val base = baseline ?: return Failure("the settings were not read.")
        // Before anything is written: a pause rule the person has not seen stops the whole save, not only its schedule part.
        if (scheduleChanged(draft.general.schedule, base)) unseenRules(base)?.let { return Failure(it) }
        for ((key, n) in draft.networks) {
            val policy = base.rules.policyFor(key)
            val speed = speedAmount(n.speedOn, n.speedText, policy.speedLimit)
            val data = amountOf(n.dataOn, n.dataText, GIB, policy.allowance?.bytes)
            val periodChanged = n.dataOn && n.period != periodOf(policy.allowance)
            if (speed == baseAmount(policy.speedLimit) && data == baseAmount(policy.allowance?.bytes) && !periodChanged) continue
            // The newest rules, so what was set elsewhere meanwhile (an upload limit, a metered mark) is kept.
            val rules = source.networkRules()
            val current = rules.policyFor(key)
            val next =
                current.copy(
                    speedLimit = (speed as? Amount.Bytes)?.bytes,
                    allowance = allowanceFor(current.allowance, data, n.period),
                )
            // A network with nothing to say, under a default that says nothing, has no entry at all.
            val entry = if (next == NetworkPolicy() && rules.default == NetworkPolicy()) null else next
            reason(source.setNetworkPolicy(key, entry))?.let { return Failure(it) }
        }
        val g = draft.general
        val t = base.transfer
        val total = speedAmount(g.totalOn, g.totalText, t.globalSpeedLimit)
        val slow = speedAmount(true, g.slowText, t.slowModeLimit, DEFAULT_SLOW_LIMIT)
        val transferChanged =
            total != baseAmount(t.globalSpeedLimit) || slow != baseAmount(t.slowModeLimit) || g.slowOn != t.slowMode ||
                g.atOnce != t.concurrencyLimit
        if (transferChanged) {
            val result =
                source.updateTransfer { settings ->
                    var next = settings
                    if (total != baseAmount(t.globalSpeedLimit)) next = next.copy(globalSpeedLimit = (total as? Amount.Bytes)?.bytes)
                    if (slow != baseAmount(t.slowModeLimit)) next = next.copy(slowModeLimit = (slow as Amount.Bytes).bytes)
                    if (g.slowOn != t.slowMode) next = next.copy(slowMode = g.slowOn)
                    if (g.atOnce != t.concurrencyLimit) next = next.copy(concurrencyLimit = g.atOnce)
                    next
                }
            reason(result)?.let { return Failure(it) }
        }
        if (scheduleChanged(g.schedule, base)) return writeSchedule(g.schedule, base)
        return null
    }

    // Why the save must stop: PAUSE rules in the engine that were not there when the dialog opened. Null when there are none.
    private suspend fun unseenRules(base: Baseline): String? {
        val known = base.scheduleRules.mapTo(mutableSetOf()) { it.id }
        val unseen = source.scheduleRules().filter { it.action == RuleAction.PAUSE && it.id !in known }
        if (unseen.isEmpty()) return null
        return "pause rules were set up while this dialog was open (${unseen.joinToString("; ", transform = ::describeRule)}). " +
            "Close the dialog and open it again to see them."
    }

    /**
     * The schedule's rules replace every PAUSE rule the engine has (rules that only slow or allow are not ours). The rules are
     * read again now, not taken from when the dialog opened: one removed since is not removed twice, and one that came since
     * (which the person has not seen) is refused rather than deleted. The new rules go in before the old ones come out, so
     * there is no moment with no pause at all (for a moment both hold: the stricter side); if one can't be added, every rule
     * that is new since before the adds (also one whose answer was lost) is taken back and the old ones stay. An old rule that
     * can't be removed does not stop the others being removed; it is counted. Null when done, else why not.
     */
    private suspend fun writeSchedule(
        schedule: DownloadSchedule,
        base: Baseline,
    ): Failure? {
        unseenRules(base)?.let { return Failure(it) }
        val before = source.scheduleRules()
        val old = before.filter { it.action == RuleAction.PAUSE }
        try {
            for (rule in scheduleRulesFor(schedule)) source.addScheduleRule(rule)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val cause = e.message ?: e::class.simpleName ?: "an unknown error"
            val seen = before.mapTo(mutableSetOf()) { it.id }
            // By difference: what the engine holds now and was not there before, whatever the adds reported.
            val stuck =
                try {
                    val strays = source.scheduleRules().filter { it.id !in seen }
                    strays.count { !takeBack(it.id) }
                } catch (read: Exception) {
                    -1
                }
            return Failure(
                when {
                    stuck == 0 -> cause
                    stuck < 0 -> "$cause. The new pause rules could not be checked and may be left over: open the dialog again."
                    else -> "$cause. ${rulesWord(stuck, "new")} couldn't be taken back."
                },
            )
        }
        val stuck = old.count { !takeBack(it.id) }
        if (stuck > 0) return Failure("Schedule saved, but ${rulesWord(stuck, "old")} couldn't be removed.", saved = true)
        return null
    }

    // Removes a rule; false only when the call threw. The engine's own false means the rule was not found, i.e. already gone,
    // which is what we wanted, so it counts as done.
    private suspend fun takeBack(id: ScheduleRuleId): Boolean =
        try {
            source.removeScheduleRule(id)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    private fun rulesWord(
        count: Int,
        which: String,
    ) = if (count == 1) "1 $which pause rule" else "$count $which pause rules"

    private fun allowanceFor(
        existing: Allowance?,
        amount: Amount,
        period: DataPeriod,
    ): Allowance? {
        val bytes = (amount as? Amount.Bytes)?.bytes ?: return null
        // The period's own details (a weekly reset day, a warning level) stay when its kind did not change.
        val kept = existing?.takeIf { periodOf(it) == period }
        val chosen =
            kept?.period
                ?: when (period) {
                    DataPeriod.DAY -> AllowancePeriod.Daily
                    DataPeriod.WEEK -> AllowancePeriod.Weekly(DayOfWeek.MONDAY)
                    DataPeriod.MONTH -> AllowancePeriod.Monthly(1)
                }
        return (existing ?: Allowance(bytes, chosen)).copy(bytes = bytes, period = chosen)
    }

    private fun reason(result: CommandResult): String? =
        when (result) {
            is CommandResult.Done -> null
            is CommandResult.Invalid -> result.reason
            else -> "the engine refused it."
        }

    private companion object {
        const val MAX_TYPED = 12
    }
}

internal const val USAGE_POLL_MS = 5_000L
