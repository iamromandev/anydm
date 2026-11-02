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

    private fun looks(block: suspend (FakeSource, NetworkVisuals, NetworkLooksModel) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            source.networks.value = BondingState(listOf(network("en0", "Wi-Fi"), network("usb0", "USB Tethering")))
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
