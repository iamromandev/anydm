package dev.anydm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class NetworkModelTest {
    private fun net(
        id: String,
        name: String = id,
        kind: NetworkKind = NetworkKind.WiFi,
        enabled: Boolean = true,
        speed: Long = 10,
    ) = Network(id, name, kind, enabled, speed)

    private val two = listOf(net("a", "Wi-Fi", speed = 7), net("b", "USB Tethering", NetworkKind.UsbTethering, speed = 5))

    @Test
    fun kindLabelsMatchTheTags() {
        assertEquals("Wi-Fi", NetworkKind.WiFi.label)
        assertEquals("USB Tethering", NetworkKind.UsbTethering.label)
    }

    @Test
    fun enabledCountSkipsDisabledNetworks() {
        assertEquals(2, enabledCount(two))
        assertEquals(1, enabledCount(listOf(two[0], two[1].copy(enabled = false))))
    }

    @Test
    fun totalSpeedSumsEnabledNetworksOnly() {
        assertEquals(12, totalSpeed(two))
        assertEquals(7, totalSpeed(listOf(two[0], two[1].copy(enabled = false))))
    }

    @Test
    fun toggleTurnsANetworkOffAndOn() {
        val off = toggle(two, "b")
        assertEquals(listOf(true, false), off.map { it.enabled })
        assertEquals(listOf(true, true), toggle(off, "b").map { it.enabled })
    }

    @Test
    fun toggleKeepsTheLastEnabledNetworkOn() {
        val one = toggle(two, "b")
        assertSame(one, toggle(one, "a"))
    }

    @Test
    fun toggleOfAnUnknownIdChangesNothing() {
        assertEquals(two, toggle(two, "zzz"))
    }

    @Test
    fun networkLabelPluralises() {
        assertEquals("1 network", networkLabel(1))
        assertEquals("2 networks", networkLabel(2))
    }

    private fun ok(r: RenameResult) = assertIs<RenameResult.Ok>(r).networks

    @Test
    fun renameStoresTheTrimmedName() {
        assertEquals(listOf("Home", "USB Tethering"), ok(rename(two, "a", "  Home  ")).map { it.name })
    }

    @Test
    fun renameToTheCurrentNameIsAccepted() {
        assertEquals(two, ok(rename(two, "a", "Wi-Fi")))
    }

    @Test
    fun renameRejectsBlankNames() {
        assertIs<RenameResult.Rejected>(rename(two, "a", ""))
        assertIs<RenameResult.Rejected>(rename(two, "a", "   "))
    }

    @Test
    fun renameAcceptsExactly24CharactersAndRejects25() {
        assertEquals("x".repeat(24), ok(rename(two, "a", "x".repeat(24))).first().name)
        assertIs<RenameResult.Rejected>(rename(two, "a", "x".repeat(25)))
    }

    @Test
    fun renameLengthIsCheckedAfterTrimming() {
        assertEquals("x".repeat(24), ok(rename(two, "a", " " + "x".repeat(24) + " ")).first().name)
    }

    @Test
    fun renameRejectsADuplicateIgnoringCase() {
        assertIs<RenameResult.Rejected>(rename(two, "a", "usb tethering"))
        assertIs<RenameResult.Rejected>(rename(two, "a", " USB TETHERING "))
    }

    @Test
    fun renameOnlyChangesTheTargetNetwork() {
        val result = ok(rename(two, "b", "Phone"))
        assertEquals(two[0], result[0])
        assertEquals(two[1].copy(name = "Phone"), result[1])
    }
}
