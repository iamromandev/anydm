package dev.anydm.desktop

import kotlinx.serialization.SerializationException
import java.nio.file.Path
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsModelTest {
    private val mb = 1024L * 1024
    private val gb = 1024L * mb
    private val secret = "hunter2-very-secret"
    private val wifi = Network("wifi", "Wi-Fi", NetworkKind.WiFi, true, 100)
    private val usb = Network("usb", "USB Tethering", NetworkKind.UsbTethering, true, 50)
    private val app = listOf(wifi, usb)
    private val defaults = initialLimits(app, mapOf("wifi" to 5 * mb))

    private fun file(f: SettingsFile.() -> SettingsFile = { this }) = SettingsFile().f()

    private fun proxyNet(
        port: Int = 1080,
        host: String = "127.0.0.1",
        type: ProxyType = ProxyType.Socks5,
    ) = NetworkDto("wifi", "Wi-Fi", null, ProxyDto(type, host, port, "alice"))

    private fun problem(f: SettingsFile) = validate(f)

    // R6, R7

    @Test
    fun aDefaultFileIsValidAndCarriesVersionOne() {
        assertNull(validate(SettingsFile()))
        assertEquals(1, SettingsFile().version)
        assertTrue(settingsJson.encodeToString(SettingsFile.serializer(), SettingsFile()).contains("\"version\": 1"))
    }

    @Test
    fun everythingPersistedRoundTripsThroughToFileAndFromFile() {
        val limits =
            defaults.copy(
                total = 20 * mb,
                slowOn = true,
                slow = 3 * mb,
                concurrency = 4,
                schedule = Schedule(true, setOf(DayOfWeek.SATURDAY, DayOfWeek.MONDAY), "08:30", "22:15"),
                networks =
                    mapOf(
                        "wifi" to NetworkLimits(7 * mb, 5 * gb, DataPeriod.Week, 5 * mb),
                        "usb" to NetworkLimits(null, 2 * gb, DataPeriod.Day, 0),
                    ),
            )
        val nets =
            listOf(
                wifi.copy(name = "Home", color = NetworkColor.Pink, proxy = Proxy(ProxyType.Socks5, "127.0.0.1", 1080, "alice", secret)),
                usb.copy(proxy = Proxy(ProxyType.Http, "proxy.example", 8080)),
            )
        val f = toFile(ThemeChoice.Dark, SpeedUnit.Mbps, "/home/x/Movies", limits, nets)
        val text = settingsJson.encodeToString(SettingsFile.serializer(), f)
        val back = settingsJson.decodeFromString(SettingsFile.serializer(), text)
        assertNull(validate(back))
        val r = fromFile(back, app, defaults)
        assertEquals(ThemeChoice.Dark, r.theme)
        assertEquals(SpeedUnit.Mbps, r.unit)
        assertEquals("/home/x/Movies", r.saveFolder)
        assertEquals(
            limits.copy(
                networks =
                    limits.networks.mapValues { (id, n) ->
                        n.copy(used = defaults.networks.getValue(id).used)
                    },
            ),
            r.limits,
        )
        assertEquals("Home", r.networks[0].name)
        assertEquals(NetworkColor.Pink, r.networks[0].color)
        assertEquals(Proxy(ProxyType.Socks5, "127.0.0.1", 1080, "alice", ""), r.networks[0].proxy)
        assertEquals(Proxy(ProxyType.Http, "proxy.example", 8080), r.networks[1].proxy)
    }

    @Test
    fun usageBytesAreNotPersistedAndComeFromTheApp() {
        val f = toFile(ThemeChoice.System, SpeedUnit.MBps, null, defaults, app)
        val text = settingsJson.encodeToString(SettingsFile.serializer(), f)
        assertFalse(text.contains("used"), text)
        assertEquals(
            5 * mb,
            fromFile(f, app, defaults)
                .limits.networks
                .getValue("wifi")
                .used,
        )
    }

    @Test
    fun aProxyPasswordNeverReachesTheFileOrItsJson() {
        val nets = listOf(wifi.copy(proxy = Proxy(ProxyType.Http, "h.example", 3128, "bob", secret)))
        val f = toFile(ThemeChoice.System, SpeedUnit.MBps, null, defaults, nets)
        val text = settingsJson.encodeToString(SettingsFile.serializer(), f)
        assertFalse(text.contains(secret), "the password is in the JSON")
        assertFalse(f.toString().contains(secret), "the password is in toString")
        assertTrue(text.contains("\"user\": \"bob\""))
    }

    @Test
    fun proxyDtoHasExactlyTheNonSecretFields() {
        val text = settingsJson.encodeToString(ProxyDto.serializer(), ProxyDto(ProxyType.Http, "h", 1, "u"))
        val keys = Regex("\"(\\w+)\":").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("type", "host", "port", "user"), keys)
    }

    @Test
    fun aNetworkWithoutAProxyIsWrittenWithoutOne() {
        val f = toFile(ThemeChoice.System, SpeedUnit.MBps, null, defaults, app)
        assertTrue(f.networks.all { it.proxy == null })
        assertEquals(listOf("wifi", "usb"), f.networks.map { it.id })
    }

    // R10

    @Test
    fun aNetworkInTheFileButNotInTheAppIsIgnored() {
        val f =
            file {
                copy(
                    networks =
                        listOf(
                            NetworkDto("gone", "Ghost", NetworkColor.Pink, ProxyDto(ProxyType.Http, "h.example", 80)),
                            NetworkDto("wifi", "Home"),
                        ),
                    limits = limits.copy(networks = mapOf("gone" to NetworkLimitsDto(1, 1), "wifi" to NetworkLimitsDto(9 * mb))),
                )
            }
        val r = fromFile(f, app, defaults)
        assertEquals(listOf("wifi", "usb"), r.networks.map { it.id })
        assertEquals(setOf("wifi", "usb"), r.limits.networks.keys)
        assertEquals("Home", r.networks[0].name)
        assertEquals(
            9 * mb,
            r.limits.networks
                .getValue("wifi")
                .speed,
        )
    }

    @Test
    fun aNetworkInTheAppButNotInTheFileGetsDefaults() {
        val f = file { copy(networks = listOf(NetworkDto("wifi", "Home", NetworkColor.Green))) }
        val r = fromFile(f, app, defaults)
        assertEquals(usb, r.networks[1])
        assertEquals(defaults.networks.getValue("usb"), r.limits.networks.getValue("usb"))
        assertEquals(NetworkColor.Green, r.networks[0].color)
    }

    @Test
    fun matchingIsByIdNotByPositionOrName() {
        val f = file { copy(networks = listOf(NetworkDto("usb", "Wi-Fi", NetworkColor.Orange), NetworkDto("wifi", "Phone"))) }
        val r = fromFile(f, app, defaults)
        assertEquals("Phone", r.networks.first { it.id == "wifi" }.name)
        assertEquals("Wi-Fi", r.networks.first { it.id == "usb" }.name)
        assertEquals(listOf("wifi", "usb"), r.networks.map { it.id })
    }

    @Test
    fun aRestoredNetworkKeepsWhatIsNotPersisted() {
        val f = file { copy(networks = listOf(NetworkDto("usb", "Tether"))) }
        val r = fromFile(f, listOf(usb.copy(enabled = false)), defaults).networks.single()
        assertEquals(NetworkKind.UsbTethering, r.kind)
        assertFalse(r.enabled)
        assertEquals(50, r.bytesPerSec)
    }

    // R9: validation

    @Test
    fun aNewerVersionIsInvalid() {
        assertNotNull(problem(file { copy(version = 99) }))
        assertNotNull(problem(file { copy(version = 0) }))
    }

    @Test
    fun aBadProxyMakesTheFileInvalid() {
        assertNotNull(problem(file { copy(networks = listOf(proxyNet(port = 70000))) }))
        assertNotNull(problem(file { copy(networks = listOf(proxyNet(port = 0))) }))
        assertNotNull(problem(file { copy(networks = listOf(proxyNet(host = "not a host"))) }))
        assertNull(problem(file { copy(networks = listOf(proxyNet())) }))
        assertNull(problem(file { copy(networks = listOf(proxyNet(port = 65535))) }))
    }

    @Test
    fun aBadConcurrencyIsInvalid() {
        for (n in listOf(0, 11, -1)) assertNotNull(problem(file { copy(limits = limits.copy(concurrency = n)) }), "$n")
        for (n in listOf(1, 10)) assertNull(problem(file { copy(limits = limits.copy(concurrency = n)) }), "$n")
    }

    @Test
    fun limitValuesMustBePositive() {
        assertNotNull(problem(file { copy(limits = limits.copy(total = 0)) }))
        assertNotNull(problem(file { copy(limits = limits.copy(total = -5)) }))
        assertNotNull(problem(file { copy(limits = limits.copy(slow = 0)) }))
        val zeroSpeed = LimitsDto().copy(networks = mapOf("wifi" to NetworkLimitsDto(speed = 0)))
        assertNotNull(problem(file { copy(limits = zeroSpeed) }))
        val negData = LimitsDto().copy(networks = mapOf("wifi" to NetworkLimitsDto(data = -1)))
        assertNotNull(problem(file { copy(limits = negData) }))
        assertNull(problem(file { copy(limits = limits.copy(total = 1)) }))
    }

    @Test
    fun anAbsurdlyLargeLimitIsInvalid() {
        assertNotNull(problem(file { copy(limits = limits.copy(total = Long.MAX_VALUE)) }))
    }

    @Test
    fun anEnabledScheduleNeedsGoodTimesAndDays() {
        fun sched(s: ScheduleDto) = file { copy(limits = limits.copy(schedule = s)) }
        assertNull(problem(sched(ScheduleDto(true, listOf("MONDAY"), "09:00", "17:00"))))
        assertNotNull(problem(sched(ScheduleDto(true, listOf("MONDAY"), "9:00", "17:00"))))
        assertNotNull(problem(sched(ScheduleDto(true, listOf("MONDAY"), "09:00", "25:00"))))
        assertNotNull(problem(sched(ScheduleDto(true, listOf("MONDAY"), "17:00", "09:00"))))
        assertNotNull(problem(sched(ScheduleDto(true, emptyList(), "09:00", "17:00"))))
    }

    @Test
    fun scheduleDayNamesMustBeRealDays() {
        assertNotNull(problem(file { copy(limits = limits.copy(schedule = ScheduleDto(false, listOf("FUNDAY")))) }))
    }

    @Test
    fun aDisabledScheduleIsNotJudgedOnItsTimesLikeTheEditor() {
        assertNull(problem(file { copy(limits = limits.copy(schedule = ScheduleDto(false, listOf("MONDAY"), "9", "x"))) }))
    }

    @Test
    fun networkIdsAndNamesMustBeUsable() {
        assertNotNull(problem(file { copy(networks = listOf(NetworkDto("", "A"))) }))
        assertNotNull(problem(file { copy(networks = listOf(NetworkDto("a", "  "))) }))
        assertNotNull(problem(file { copy(networks = listOf(NetworkDto("a", "x".repeat(MAX_NETWORK_NAME_LENGTH + 1)))) }))
        assertNotNull(problem(file { copy(networks = listOf(NetworkDto("a", "A"), NetworkDto("a", "B"))) }))
        assertNull(problem(file { copy(networks = listOf(NetworkDto("a", "x".repeat(MAX_NETWORK_NAME_LENGTH)))) }))
    }

    @Test
    fun aBlankSaveFolderIsInvalidButNullIsFine() {
        assertNotNull(problem(file { copy(saveFolder = " ") }))
        assertNull(problem(file { copy(saveFolder = null) }))
        assertNull(problem(file { copy(saveFolder = "/a/b") }))
    }

    @Test
    fun aSaveFolderThatIsNotAbsoluteIsInvalid() {
        assertNotNull(problem(file { copy(saveFolder = "Downloads/anydm") }))
        assertNotNull(problem(file { copy(saveFolder = "../x") }))
        assertNotNull(problem(file { copy(saveFolder = "/a/b\u0000c") }))
    }

    // R9: parsing

    @Test
    fun unknownFieldsAreIgnored() {
        val text = """{"version":1,"theme":"Dark","future":{"x":1},"networks":[{"id":"wifi","name":"Home","extra":true}]}"""
        val f = settingsJson.decodeFromString(SettingsFile.serializer(), text)
        assertEquals(ThemeChoice.Dark, f.theme)
        assertEquals("Home", f.networks.single().name)
        assertNull(validate(f))
    }

    @Test
    fun missingFieldsTakeDefaults() {
        val f = settingsJson.decodeFromString(SettingsFile.serializer(), """{"version":1}""")
        assertEquals(SettingsFile(), f)
    }

    @Test
    fun anUnknownEnumNameOrAWrongTypeIsAParseError() {
        assertFailsWith<SerializationException> { settingsJson.decodeFromString(SettingsFile.serializer(), """{"theme":"Purple"}""") }
        assertFailsWith<SerializationException> {
            settingsJson.decodeFromString(
                SettingsFile.serializer(),
                """{"limits":{"concurrency":"two"}}""",
            )
        }
        assertFailsWith<SerializationException> { settingsJson.decodeFromString(SettingsFile.serializer(), "not json") }
    }

    // R7, AC7

    private val noEnv = emptyMap<String, String>()

    @Test
    fun macUsesApplicationSupport() {
        assertEquals(Path.of("/Users/a/Library/Application Support/anydm"), appDataDir(Platform.Mac, noEnv, "/Users/a", "mac os x"))
    }

    @Test
    fun windowsUsesAppData() {
        val env = mapOf("APPDATA" to "/win/Roaming")
        assertEquals(Path.of("/win/Roaming", "anydm"), appDataDir(Platform.Other, env, "/Users/a", "Windows 11"))
    }

    @Test
    fun windowsWithoutAppDataFallsBackUnderHome() {
        assertEquals(Path.of("/Users/a", "AppData", "Roaming", "anydm"), appDataDir(Platform.Other, noEnv, "/Users/a", "Windows 11"))
    }

    @Test
    fun linuxUsesXdgConfigHomeWhenSet() {
        val env = mapOf("XDG_CONFIG_HOME" to "/xdg/cfg")
        assertEquals(Path.of("/xdg/cfg/anydm"), appDataDir(Platform.Other, env, "/home/a", "Linux"))
    }

    @Test
    fun linuxDefaultsToDotConfig() {
        assertEquals(Path.of("/home/a/.config/anydm"), appDataDir(Platform.Other, noEnv, "/home/a", "Linux"))
    }

    @Test
    fun linuxIgnoresABlankOrRelativeXdgConfigHome() {
        assertEquals(Path.of("/home/a/.config/anydm"), appDataDir(Platform.Other, mapOf("XDG_CONFIG_HOME" to " "), "/home/a", "Linux"))
        assertEquals(Path.of("/home/a/.config/anydm"), appDataDir(Platform.Other, mapOf("XDG_CONFIG_HOME" to "rel/x"), "/home/a", "Linux"))
    }

    @Test
    fun theWindowsEnvVariableDoesNotAffectLinuxOrMac() {
        val env = mapOf("APPDATA" to "/win/Roaming")
        assertEquals(Path.of("/home/a/.config/anydm"), appDataDir(Platform.Other, env, "/home/a", "Linux"))
        assertEquals(Path.of("/Users/a/Library/Application Support/anydm"), appDataDir(Platform.Mac, env, "/Users/a", "Mac OS X"))
    }
}
