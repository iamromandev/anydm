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

    @Test
    fun setColorChangesOnlyTheTargetNetwork() {
        val out = setColor(two, "b", NetworkColor.Orange)
        assertEquals(listOf(null, NetworkColor.Orange), out.map { it.color })
        assertEquals(two.map { it.name }, out.map { it.name })
    }

    @Test
    fun setColorOfAnUnknownIdChangesNothing() {
        assertEquals(two, setColor(two, "zzz", NetworkColor.Pink))
    }

    @Test
    fun setColorSurvivesToggleAndRename() {
        val coloured = setColor(two, "b", NetworkColor.Teal)
        assertEquals(NetworkColor.Teal, toggle(coloured, "b")[1].color)
        val renamed = assertIs<RenameResult.Ok>(rename(coloured, "b", "Phone")).networks
        assertEquals(NetworkColor.Teal, renamed[1].color)
    }

    private fun draft(
        type: ProxyType = ProxyType.Http,
        host: String = "10.0.0.2",
        port: String = "8080",
        user: String = "",
        password: String = "",
    ) = ProxyDraft(type, host, port, user, password)

    @Test
    fun isHostAcceptsNamesAndIpv4() {
        for (h in listOf("10.0.0.2", "my-proxy", "Proxy.Example.COM", "example.com.", "a", "127.0.0.1", "1-2.net")) {
            assertEquals(true, isHost(h), h)
        }
    }

    @Test
    fun isHostRequiresFourByteLabelsWhenEveryLabelIsNumeric() {
        for (h in listOf("0.0.0.0", "255.255.255.255", "1.2.3.4.")) assertEquals(true, isHost(h), h)
        for (h in listOf("999.1.1.1", "1.2.3", "1.2.3.4.5", "256.0.0.1", "1.2.3.300", "10", "1.2")) assertEquals(false, isHost(h), h)
        assertEquals(true, isHost("1.2.3.x"))
        assertEquals(true, isHost("3com.example"))
    }

    @Test
    fun isHostLimitsLabelsTo63Characters() {
        assertEquals(true, isHost("a".repeat(63) + ".com"))
        assertEquals(false, isHost("a".repeat(64) + ".com"))
        assertEquals(false, isHost("ok." + "b".repeat(64)))
    }

    @Test
    fun isHostRejectsSchemesSpacesPathsPortsAndIpv6() {
        for (h in listOf(
            "",
            "my proxy",
            "http://x",
            "x/y",
            "x:80",
            "::1",
            "[::1]",
            "-a.com",
            "a..b",
            ".",
            ".a",
            "a_b",
            "h\u00f6st",
            "a b.com",
            "x?y",
        )) {
            assertEquals(false, isHost(h), h)
        }
    }

    @Test
    fun noneProxyHasNoErrorsWhateverTheFields() {
        assertEquals(false, proxyErrors(draft(ProxyType.None, host = "bad host", port = "0", password = "x")).any)
    }

    @Test
    fun validHttpDraftHasNoErrors() {
        assertEquals(false, proxyErrors(draft()).any)
    }

    @Test
    fun invalidHostIsReported() {
        assertEquals("Enter a host name or IPv4 address", proxyErrors(draft(host = "http://x")).host)
        assertEquals("Enter a host name or IPv4 address", proxyErrors(draft(host = "")).host)
    }

    @Test
    fun portMustBeBetween1And65535() {
        for (p in listOf("", "0", "65536", "70000", "abc", "80a", "-1", " 80", "1.5", "99999999999999999999")) {
            assertEquals("Port must be 1\u201365535", proxyErrors(draft(port = p)).port, p)
        }
        for (p in listOf("1", "80", "0080", "65535")) assertEquals(null, proxyErrors(draft(port = p)).port, p)
    }

    @Test
    fun passwordWithoutUsernameIsAnAuthError() {
        assertEquals("Add a username for this password", proxyErrors(draft(password = "x")).auth)
        assertEquals(null, proxyErrors(draft(user = "a", password = "x")).auth)
        assertEquals(null, proxyErrors(draft(user = "a")).auth)
    }

    @Test
    fun proxyOfBuildsTheProxyAndParsesLeadingZeros() {
        assertEquals(
            Proxy(ProxyType.Socks5, "127.0.0.1", 80, "alice", "pw"),
            proxyOf(draft(ProxyType.Socks5, "127.0.0.1", "0080", "alice", "pw")),
        )
    }

    @Test
    fun proxyOfIsNullWhenInvalidAndNoneWhenNone() {
        assertEquals(null, proxyOf(draft(port = "0")))
        assertEquals(Proxy.NONE, proxyOf(draft(ProxyType.None, host = "junk", port = "0")))
    }

    @Test
    fun draftOfRoundTripsAndNoneIsBlank() {
        val p = Proxy(ProxyType.Http, "h.example", 3128, "u", "p")
        assertEquals(p, proxyOf(draftOf(p)))
        assertEquals(draft(ProxyType.None, "", "", "", ""), draftOf(Proxy.NONE))
    }

    @Test
    fun proxySummaryFormats() {
        assertEquals("None", proxySummary(Proxy.NONE))
        assertEquals("HTTP 10.0.0.2:8080", proxySummary(Proxy(ProxyType.Http, "10.0.0.2", 8080)))
        assertEquals("SOCKS5 alice@127.0.0.1:1080", proxySummary(Proxy(ProxyType.Socks5, "127.0.0.1", 1080, "alice", "secret")))
    }

    @Test
    fun proxySummaryNeverIncludesThePassword() {
        assertEquals(false, proxySummary(Proxy(ProxyType.Http, "h", 1, "u", "hunter2")).contains("hunter2"))
    }

    @Test
    fun setProxyChangesOnlyTheTargetNetwork() {
        val p = Proxy(ProxyType.Http, "h", 8080)
        val out = setProxy(two, "b", p)
        assertEquals(listOf(Proxy.NONE, p), out.map { it.proxy })
        assertEquals(two.map { it.name }, out.map { it.name })
        assertEquals(two, setProxy(two, "zzz", p))
    }

    @Test
    fun proxyToStringMasksThePassword() {
        val text = Proxy(ProxyType.Http, "h", 1, "u", "hunter2").toString()
        assertEquals(false, text.contains("hunter2"))
        assertEquals(true, text.contains("password=***"))
        assertEquals("Proxy(type=None, host=, port=0, user=, password=)", Proxy.NONE.toString())
    }

    @Test
    fun draftToStringMasksThePassword() {
        assertEquals(false, draft(password = "hunter2").toString().contains("hunter2"))
    }

    @Test
    fun setProxyKeepsColorAndSurvivesToggle() {
        val p = Proxy(ProxyType.Http, "h", 8080)
        val out = setProxy(setColor(two, "b", NetworkColor.Pink), "b", p)
        assertEquals(NetworkColor.Pink, out[1].color)
        assertEquals(p, toggle(out, "b")[1].proxy)
    }
}
