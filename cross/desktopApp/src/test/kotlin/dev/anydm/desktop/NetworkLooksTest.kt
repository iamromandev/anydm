package dev.anydm.desktop

import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
internal class NetworkLooksTest {
    private fun network(
        key: String,
        name: String = key,
    ) = BondedNetwork(NetworkInfo(key, name, true, emptyList()), true, BindStatus.ISOLATED, null, false)

    private fun looks(
        initial: List<BondedNetwork> = listOf(network("en0", "Wi-Fi"), network("usb0", "USB Tethering")),
        block: suspend (FakeSource, NetworkVisuals, NetworkLooksModel) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.networks.value = BondingState(initial)
        val visuals = NetworkVisuals(null)
        block(source, visuals, NetworkLooksModel(source, visuals, backgroundScope))
    }

    @Test
    fun `networks start with the hue of their kind and no custom names`() =
        looks { _, _, model ->
            assertEquals(mapOf("en0" to "teal", "usb0" to "amber"), model.looks.value.colorIds)
            assertEquals(emptyMap(), model.looks.value.names)
        }

    @Test
    fun `a rename reaches every screen through the looks`() =
        looks { _, visuals, model ->
            visuals.rename("usb0", "Pixel")
            assertEquals("Pixel", model.looks.value.nameOf("usb0", "USB Tethering"))
            assertEquals("Wi-Fi", model.looks.value.nameOf("en0", "Wi-Fi"))
        }

    @Test
    fun `a network with no name given shows a friendly name by the kind its OS name gives`() =
        looks(emptyList()) { source, _, model ->
            source.networks.value =
                BondingState(listOf(network("w", "Intel Wi-Fi"), network("usb0", "USB Tethering"), network("e", "Realtek Ethernet")))
            val looks = model.looks.value
            assertEquals("Wi-Fi", looks.nameOf("w", "Intel Wi-Fi"))
            assertEquals("USB", looks.nameOf("usb0", "USB Tethering"))
            assertEquals("Ethernet", looks.nameOf("e", "Realtek Ethernet"))
        }

    @Test
    fun `a kind decided by the key alone gets no friendly name`() =
        looks { source, _, model ->
            source.networks.value =
                BondingState(
                    listOf(network("en0", "en0"), network("enx1a2b", "enx1a2b"), network("eth0", "eth0"), network("utun3", "utun3")),
                )
            val looks = model.looks.value
            assertEquals("en0", looks.nameOf("en0", "en0"))
            assertEquals("enx1a2b", looks.nameOf("enx1a2b", "enx1a2b"))
            assertEquals("eth0", looks.nameOf("eth0", "eth0"))
            assertEquals("utun3", looks.nameOf("utun3", "utun3"))
        }

    @Test
    fun `two networks of one kind keep their OS names instead of the same friendly one`() =
        looks(emptyList()) { source, _, model ->
            source.networks.value =
                BondingState(listOf(network("eth0", "Ethernet"), network("eth1", "Ethernet 2"), network("wlan0", "Wi-Fi")))
            val looks = model.looks.value
            assertEquals("Ethernet", looks.nameOf("eth0", "Ethernet"))
            assertEquals("Ethernet 2", looks.nameOf("eth1", "Ethernet 2"))
            assertEquals("Wi-Fi", looks.nameOf("wlan0", "Fi"))
            assertEquals(mapOf("wlan0" to "Wi-Fi"), looks.defaults)
        }

    @Test
    fun `a friendly name is dropped once a second network of its kind appears`() =
        looks(emptyList()) { source, _, model ->
            source.networks.value = BondingState(listOf(network("eth0", "Ethernet")))
            assertEquals("Ethernet", model.looks.value.defaults["eth0"])
            source.networks.value = BondingState(listOf(network("eth0", "Ethernet"), network("eth1", "Ethernet 2")))
            assertEquals(emptyMap(), model.looks.value.defaults)
        }

    @Test
    fun `nameOf falls back to the OS name when the key has no kind or no default`() {
        val looks = NetworkLooks(kinds = mapOf("en0" to NetworkKind.WIFI))
        assertEquals("Other", looks.nameOf("missing", "Other"))
        assertEquals("en0", looks.nameOf("en0", "en0"))
    }

    @Test
    fun `a network that disappears keeps its kind and friendly name`() =
        looks { source, _, model ->
            source.networks.value = BondingState(listOf(network("usb0", "USB Tethering")))
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a", total = 10, networkShare = mapOf("usb0" to 5L)))
            source.networks.value = BondingState(emptyList())
            assertEquals(NetworkKind.USB, model.looks.value.kinds["usb0"])
            assertEquals("USB", model.looks.value.nameOf("usb0", "usb0"))
        }

    @Test
    fun `a rename wins over the friendly name`() =
        looks { source, visuals, model ->
            source.networks.value = BondingState(listOf(network("en0", "en0")))
            visuals.rename("en0", "Home")
            assertEquals("Home", model.looks.value.nameOf("en0", "en0"))
        }

    @Test
    fun `a picked color replaces the hue, and the swatch is the theme's`() =
        looks { _, visuals, model ->
            visuals.pick("usb0", "rose")
            assertEquals("rose", model.looks.value.colorIds["usb0"])
            assertEquals(
                DarkColors.swatches[3],
                model.looks.value
                    .swatches(DarkColors)
                    .getValue("usb0"),
            )
            assertEquals(
                LightColors.swatches[3],
                model.looks.value
                    .swatches(LightColors)
                    .getValue("usb0"),
            )
        }

    @Test
    fun `a network that goes away keeps its color while a download carried bytes over it`() =
        looks { source, visuals, model ->
            visuals.pick("usb0", "violet")
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a", total = 10, networkShare = mapOf("usb0" to 5L)))
            source.networks.value = BondingState(listOf(network("en0", "Wi-Fi")))
            assertEquals("violet", model.looks.value.colorIds["usb0"])
            assertEquals("teal", model.looks.value.colorIds["en0"])
        }

    @Test
    fun `a network that appears does not recolor one a person picked`() =
        looks { source, visuals, model ->
            visuals.pick("en0", "coral")
            source.networks.value = BondingState(listOf(network("en0"), network("usb0"), network("eth0")))
            assertEquals("coral", model.looks.value.colorIds["en0"])
            assertEquals("steel", model.looks.value.colorIds["eth0"])
        }
}
