package dev.anydm.desktop

import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LimitsModelTest {
    private val mb = 1024L * 1024
    private val gb = 1024L * mb
    private val nets =
        listOf(
            Network("wifi", "Wi-Fi", NetworkKind.WiFi, true, 0),
            Network("usb", "USB Tethering", NetworkKind.UsbTethering, false, 0),
        )
    private val seed = initialLimits(nets, mapOf("wifi" to 5 * mb))

    private fun draft(unit: SpeedUnit = SpeedUnit.MBps) = draftOf(seed, unit, false)

    private fun LimitsDraft.editGeneral(f: (GeneralDraft) -> GeneralDraft) = copy(general = f(general))

    private fun LimitsDraft.net(
        id: String,
        f: (NetworkDraft) -> NetworkDraft,
    ) = copy(networks = networks + (id to f(networks.getValue(id))))

    @Test
    fun parseAmountAcceptsPositiveNumbersWithOneDecimal() {
        assertEquals(20.0, parseAmount("20"))
        assertEquals(0.5, parseAmount("0.5"))
        assertEquals(100_000.0, parseAmount("100000"))
    }

    @Test
    fun parseAmountRejectsBadInput() {
        for (bad in listOf("", " ", "0", "0.0", "1,5", "1.55", "-3", "abc", "1e3", ".5", "5.", "100001", "100000.1")) {
            assertNull(parseAmount(bad), "'$bad'")
        }
    }

    @Test
    fun parseTimeReadsHoursAndMinutes() {
        assertEquals(9 * 60, parseTime("09:00"))
        assertEquals(23 * 60 + 59, parseTime("23:59"))
        assertEquals(0, parseTime("00:00"))
    }

    @Test
    fun parseTimeRejectsBadInput() {
        for (bad in listOf("", "9:00", "24:00", "12:60", "12-00", "ab:cd", "09:0")) assertNull(parseTime(bad), "'$bad'")
    }

    @Test
    fun defaultScheduleIsWeekdaysNineToFive() {
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            DEFAULT_SCHEDULE.days,
        )
        assertEquals("09:00", DEFAULT_SCHEDULE.start)
        assertEquals("17:00", DEFAULT_SCHEDULE.end)
        assertFalse(DEFAULT_SCHEDULE.enabled)
    }

    @Test
    fun scheduleIsValidWhenDisabledEvenIfBroken() {
        assertNull(scheduleError(DEFAULT_SCHEDULE.copy(enabled = false, days = emptySet(), start = "x")))
    }

    @Test
    fun scheduleNeedsADayValidTimesAndEndAfterStart() {
        val on = DEFAULT_SCHEDULE.copy(enabled = true)
        assertNull(scheduleError(on))
        assertNotNull(scheduleError(on.copy(days = emptySet())))
        assertNotNull(scheduleError(on.copy(start = "9am")))
        assertNotNull(scheduleError(on.copy(end = "")))
        assertNotNull(scheduleError(on.copy(end = "09:00")))
        assertNotNull(scheduleError(on.copy(end = "08:00")))
    }

    @Test
    fun unitLabels() {
        assertEquals("MB/s", unitLabel(SpeedUnit.MBps))
        assertEquals("Mbps", unitLabel(SpeedUnit.Mbps))
    }

    @Test
    fun initialLimitsHasNoLimitsAndTakesUsageFromTheMap() {
        assertNull(seed.total)
        assertFalse(seed.slowOn)
        assertEquals(2 * mb, seed.slow)
        assertEquals(2, seed.concurrency)
        assertEquals(DEFAULT_SCHEDULE, seed.schedule)
        assertEquals(setOf("wifi", "usb"), seed.networks.keys)
        assertEquals(5 * mb, seed.networks.getValue("wifi").used)
        assertEquals(0L, seed.networks.getValue("usb").used)
        assertNull(seed.networks.getValue("wifi").speed)
        assertNull(seed.networks.getValue("wifi").data)
        assertEquals(DataPeriod.Month, seed.networks.getValue("wifi").period)
    }

    @Test
    fun untouchedDraftSavesAsTheSameLimits() {
        assertEquals(seed, limitsOf(draft(), SpeedUnit.MBps))
        assertEquals(seed, limitsOf(draft(SpeedUnit.Mbps), SpeedUnit.Mbps))
    }

    @Test
    fun draftOfShowsStoredValuesInTheUnit() {
        val l = seed.copy(total = 20 * mb, slow = 3 * mb, slowOn = true)
        val mbps = draftOf(l, SpeedUnit.MBps, true).general
        assertEquals(LimitField(true, "20"), mbps.total.copy(kept = null))
        assertEquals("3", mbps.slowText)
        assertTrue(mbps.slowOn)
        val bits = draftOf(l, SpeedUnit.Mbps, true).general
        assertEquals(LimitField(true, "160"), bits.total.copy(kept = null))
        assertEquals("24", bits.slowText)
    }

    @Test
    fun draftOfShowsOneDecimalAndDropsTrailingZero() {
        val l = seed.copy(total = (2.5 * mb).toLong())
        assertEquals("2.5", draftOf(l, SpeedUnit.MBps, false).general.total.text)
        assertEquals("20", draftOf(l, SpeedUnit.Mbps, false).general.total.text)
    }

    @Test
    fun limitsOfConvertsTheDisplayUnitToBytes() {
        val d = draft(SpeedUnit.Mbps).editGeneral { it.copy(total = LimitField(true, "160")) }
        assertEquals(20 * mb, limitsOf(d, SpeedUnit.Mbps)?.total)
        val m = draft().editGeneral { it.copy(total = LimitField(true, "20")) }
        assertEquals(20 * mb, limitsOf(m, SpeedUnit.MBps)?.total)
    }

    @Test
    fun limitsOfRoundsToTheNearestByte() {
        val d = draft(SpeedUnit.Mbps).editGeneral { it.copy(total = LimitField(true, "0.1")) }
        assertEquals(13_107L, limitsOf(d, SpeedUnit.Mbps)?.total)
    }

    @Test
    fun limitsOfConvertsNetworkSpeedAndDataLimit() {
        val d =
            draft()
                .net("wifi") { it.copy(speed = LimitField(true, "10"), data = LimitField(true, "5"), period = DataPeriod.Week) }
        val n = limitsOf(d, SpeedUnit.MBps)!!.networks.getValue("wifi")
        assertEquals(10 * mb, n.speed)
        assertEquals(5 * gb, n.data)
        assertEquals(DataPeriod.Week, n.period)
        assertEquals(5 * mb, n.used)
    }

    @Test
    fun limitFieldOffMeansNoLimitWhateverTheText() {
        val d = draft().editGeneral { it.copy(total = LimitField(false, "abc")) }
        assertNull(limitsOf(d, SpeedUnit.MBps)?.total)
        assertTrue(errorPanes(d).isEmpty())
    }

    @Test
    fun limitsOfCarriesSlowConcurrencyAndSchedule() {
        val sched = DEFAULT_SCHEDULE.copy(enabled = true, start = "08:30")
        val d = draft().editGeneral { it.copy(slowOn = true, slowText = "4", concurrency = 7, schedule = sched) }
        val l = limitsOf(d, SpeedUnit.MBps)!!
        assertTrue(l.slowOn)
        assertEquals(4 * mb, l.slow)
        assertEquals(7, l.concurrency)
        assertEquals(sched, l.schedule)
    }

    @Test
    fun errorPanesIsEmptyForAValidDraft() {
        assertEquals(emptySet(), errorPanes(draft()))
    }

    @Test
    fun emptyOrZeroLimitMarksTheGeneralPane() {
        for (bad in listOf("", "0", "1,5", "100001")) {
            val d = draft().editGeneral { it.copy(total = LimitField(true, bad)) }
            assertEquals(setOf("general"), errorPanes(d), "'$bad'")
            assertNull(limitsOf(d, SpeedUnit.MBps))
        }
    }

    @Test
    fun invalidSlowTextIsAnErrorWhenSlowModeIsOn() {
        val d = draft().editGeneral { it.copy(slowOn = true, slowText = "") }
        assertEquals(setOf("general"), errorPanes(d))
        assertNull(limitsOf(d, SpeedUnit.MBps))
    }

    @Test
    fun invalidSlowTextNeverBlocksSaveWhileSlowModeIsOffAndKeepsThePreviousValue() {
        val d = draft().editGeneral { it.copy(slowOn = false, slowText = "") }
        assertEquals(emptySet(), errorPanes(d))
        assertEquals(seed.slow, limitsOf(d, SpeedUnit.MBps)?.slow)
        assertEquals(seed.slow, limitsOf(d.editGeneral { it.copy(slowText = "abc") }, SpeedUnit.MBps)?.slow)
    }

    @Test
    fun validSlowTextIsSavedWhileSlowModeIsOff() {
        val d = draft().editGeneral { it.copy(slowOn = false, slowText = "4") }
        assertEquals(4 * mb, limitsOf(d, SpeedUnit.MBps)?.slow)
    }

    @Test
    fun fiveMbpsReopenedInMegabytesSavesTheSameBytesWithoutEdits() {
        val l = seed.copy(total = 655_360L, slow = 655_360L)
        val d = draftOf(l, SpeedUnit.MBps, false)
        assertEquals("0.6", d.general.total.text)
        assertEquals(l, limitsOf(d, SpeedUnit.MBps))
    }

    @Test
    fun editingAFieldStopsKeepingTheOriginalBytes() {
        val l = seed.copy(total = 655_360L)
        val d = draftOf(l, SpeedUnit.MBps, false).editGeneral { it.copy(total = it.total.copy(text = "1")) }
        assertEquals(mb, limitsOf(d, SpeedUnit.MBps)?.total)
    }

    @Test
    fun tinyStoredValueIsShownWithPrecisionAndDoesNotBlockSave() {
        val l = seed.copy(total = 13_107L)
        val d = draftOf(l, SpeedUnit.MBps, false)
        assertEquals("0.012", d.general.total.text)
        assertEquals(emptySet(), errorPanes(d))
        assertEquals(l, limitsOf(d, SpeedUnit.MBps))
    }

    @Test
    fun tinyStoredNetworkDataLimitSurvivesAnUntouchedSave() {
        val n = seed.networks.getValue("wifi").copy(speed = 13_107L, data = 1_000_000L)
        val l = seed.copy(networks = seed.networks + ("wifi" to n))
        assertEquals(l, limitsOf(draftOf(l, SpeedUnit.MBps, false), SpeedUnit.MBps))
    }

    @Test
    fun badTimeFieldsAreFlaggedPerField() {
        val s = DEFAULT_SCHEDULE.copy(enabled = true)
        assertFalse(badStart(s))
        assertFalse(badEnd(s))
        assertTrue(badStart(s.copy(start = "xx")))
        assertFalse(badEnd(s.copy(start = "xx")))
        assertTrue(badEnd(s.copy(end = "xx")))
        assertTrue(badEnd(s.copy(end = "08:00")))
        assertFalse(badStart(s.copy(end = "08:00")))
        assertFalse(badEnd(s.copy(days = emptySet())))
        assertFalse(badStart(s.copy(enabled = false, start = "xx")))
    }

    @Test
    fun scheduleErrorMarksTheGeneralPane() {
        val d = draft().editGeneral { it.copy(schedule = DEFAULT_SCHEDULE.copy(enabled = true, days = emptySet())) }
        assertEquals(setOf("general"), errorPanes(d))
    }

    @Test
    fun networkErrorsMarkOnlyThatNetwork() {
        val d = draft().net("wifi") { it.copy(speed = LimitField(true, "0")) }
        assertEquals(setOf("wifi"), errorPanes(d))
        val e = d.net("usb") { it.copy(data = LimitField(true, "")) }
        assertEquals(setOf("wifi", "usb"), errorPanes(e))
        assertNull(limitsOf(e, SpeedUnit.MBps))
    }

    @Test
    fun summaryGeneral() {
        val g = draft().general
        assertEquals("No limit", summaryGeneral(g, SpeedUnit.MBps))
        assertEquals("20 MB/s", summaryGeneral(g.copy(total = LimitField(true, "20")), SpeedUnit.MBps))
        assertEquals("160 Mbps", summaryGeneral(g.copy(total = LimitField(true, "160")), SpeedUnit.Mbps))
        assertEquals("Slow · 2 MB/s", summaryGeneral(g.copy(slowOn = true, total = LimitField(true, "20")), SpeedUnit.MBps))
    }

    @Test
    fun summaryGeneralShowsInvalidForABadTotal() {
        val g = draft().general
        assertEquals("Invalid", summaryGeneral(g.copy(total = LimitField(true, "")), SpeedUnit.MBps))
        assertEquals("Invalid", summaryGeneral(g.copy(total = LimitField(true, "0")), SpeedUnit.MBps))
    }

    @Test
    fun summaryGeneralIgnoresASlowTextWhileSlowModeIsOff() {
        val g = draft().general.copy(slowText = "")
        assertEquals("No limit", summaryGeneral(g, SpeedUnit.MBps))
    }

    @Test
    fun summaryNetwork() {
        val n = draft().networks.getValue("wifi")
        assertEquals("No limit", summaryNetwork(n, SpeedUnit.MBps))
        val speed = n.copy(speed = LimitField(true, "10"))
        assertEquals("10 MB/s", summaryNetwork(speed, SpeedUnit.MBps))
        val data = n.copy(data = LimitField(true, "5"), period = DataPeriod.Month)
        assertEquals("5 GB/Month", summaryNetwork(data, SpeedUnit.MBps))
        assertEquals("10 MB/s · 5 GB/Month", summaryNetwork(speed.copy(data = data.data, period = DataPeriod.Month), SpeedUnit.MBps))
        assertEquals("5 GB/Day", summaryNetwork(data.copy(period = DataPeriod.Day), SpeedUnit.MBps))
    }

    @Test
    fun defaultGeneralHasNoLimitSlowOffAndTwoAtOnce() {
        val g = defaultGeneral(SpeedUnit.MBps)
        assertFalse(g.total.on)
        assertFalse(g.slowOn)
        assertEquals("2", g.slowText)
        assertEquals(2, g.concurrency)
        assertEquals(DEFAULT_SCHEDULE, g.schedule)
        assertEquals("16", defaultGeneral(SpeedUnit.Mbps).slowText)
    }

    @Test
    fun removeLimitsTurnsBothOff() {
        val n = draft().networks.getValue("wifi").copy(speed = LimitField(true, "10"), data = LimitField(true, "5"))
        val r = removeLimits(n)
        assertFalse(r.speed.on)
        assertFalse(r.data.on)
        assertEquals(n.used, r.used)
        assertEquals(n.period, r.period)
    }

    @Test
    fun rebaseConvertsAnEditedValueToTheNewUnit() {
        val d =
            draft()
                .editGeneral {
                    it.copy(total = LimitField(true, "10"), slowText = "4")
                }.net("wifi") { it.copy(speed = LimitField(true, "5")) }
        val r = rebaseDraft(d, SpeedUnit.MBps, SpeedUnit.Mbps)
        assertEquals("80", r.general.total.text)
        assertEquals("32", r.general.slowText)
        assertEquals(
            "40",
            r.networks
                .getValue("wifi")
                .speed.text,
        )
        val saved = limitsOf(r, SpeedUnit.Mbps)
        assertNotNull(saved)
        assertEquals(10 * mb, saved.total)
        assertEquals(4 * mb, saved.slow)
        assertEquals(5 * mb, saved.networks.getValue("wifi").speed)
    }

    @Test
    fun rebaseKeepsTheExactSavedBytesOfAnUntouchedValue() {
        val limits = seed.copy(total = 1_000_000L)
        val d = draftOf(limits, SpeedUnit.MBps, false)
        val r = rebaseDraft(d, SpeedUnit.MBps, SpeedUnit.Mbps)
        assertEquals("7.6", r.general.total.text)
        assertEquals(1_000_000L, limitsOf(r, SpeedUnit.Mbps)?.total)
    }

    @Test
    fun rebaseLeavesAnInvalidTextAndAnOffFieldAlone() {
        val d = draft().editGeneral { it.copy(total = LimitField(true, "1,5")) }.net("usb") { it.copy(speed = LimitField(false, "7")) }
        val r = rebaseDraft(d, SpeedUnit.MBps, SpeedUnit.Mbps)
        assertEquals("1,5", r.general.total.text)
        assertEquals(setOf(GENERAL_PANE), errorPanes(r))
        assertEquals(
            "7",
            r.networks
                .getValue("usb")
                .speed.text,
        )
    }

    @Test
    fun rebaseDoesNotTouchDataLimitsOrOtherFields() {
        val d =
            draft()
                .net(
                    "wifi",
                ) { it.copy(data = LimitField(true, "5"), used = 123) }
                .editGeneral { it.copy(concurrency = 4, slowOn = true) }
        val r = rebaseDraft(d, SpeedUnit.MBps, SpeedUnit.Mbps)
        assertEquals(
            "5",
            r.networks
                .getValue("wifi")
                .data.text,
        )
        assertEquals(123, r.networks.getValue("wifi").used)
        assertEquals(4, r.general.concurrency)
        assertTrue(r.general.slowOn)
    }

    @Test
    fun rebaseToTheSameUnitChangesNothing() {
        val d = draft().editGeneral { it.copy(total = LimitField(true, "10")) }
        assertEquals(d, rebaseDraft(d, SpeedUnit.MBps, SpeedUnit.MBps))
    }
}
