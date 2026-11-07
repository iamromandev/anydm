package dev.anydm.desktop

import dev.anydm.engine.http.HttpRequest
import dev.anydm.engine.http.HttpResponse
import dev.anydm.engine.http.HttpTransport
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BoundSocketFactories
import dev.anydm.engine.net.DesktopNetworks
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.platform.AppDirs
import dev.anydm.engine.platform.NetworkBinder
import dev.anydm.engine.platform.NetworkLister
import dev.anydm.engine.platform.desktopAppDirs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class DesktopWiringTest {
    private fun wiring(seen: MutableList<BoundSocketFactories?>) =
        DesktopNetworking(transportOn = { bound ->
            seen += bound
            Stub()
        })

    @Test
    fun `one network set serves as lister, binder and the sockets of both transports`() {
        val seen = mutableListOf<BoundSocketFactories?>()
        val net = wiring(seen)
        assertIs<DesktopNetworks>(net.networks)
        assertSame(net.networks, net.lister)
        assertSame(net.networks, net.binder)
        assertEquals(2, seen.size)
        seen.forEach { assertSame(net.networks, it) }
        assertNotSame(net.httpTransport, net.doctorTransport)
    }

    @Test
    fun `the default networking builds real OkHttp transports on the shared networks`() {
        val net = DesktopNetworking()
        assertIs<OkHttpTransport>(net.httpTransport)
        assertIs<OkHttpTransport>(net.doctorTransport)
        assertNotSame(net.httpTransport, net.doctorTransport)
    }

    @Test
    fun `the engine lists the networks of the lister and binder it is given`() =
        runTest {
            val root = Files.createTempDirectory("anydm-wiring")
            try {
                val dirs =
                    AppDirs(
                        Path(root.resolve("data").toString()),
                        Path(root.resolve("logs").toString()),
                        Path(root.resolve("cache").toString()),
                    )
                val fake = NetworkInfo("en9", "Fake", true, listOf("10.9.0.1"), 9)
                var probed: String? = null
                val source =
                    openDesktopSource(dirs) {
                        DesktopNetworking(
                            it,
                            transportOn = { Stub() },
                            listerOf = { NetworkLister { listOf(fake) } },
                            binderOf = {
                                NetworkBinder { network ->
                                    probed = network.key
                                    BindStatus.ISOLATED
                                }
                            },
                        )
                    }
                try {
                    assertTrue(source !is UnavailableSource, "the source must open")
                    source.start()
                    assertEquals(
                        listOf("en9"),
                        source.networks
                            .first { it.networks.isNotEmpty() }
                            .networks
                            .map { it.info.key },
                    )
                    assertEquals("en9", probed)
                } finally {
                    source.stop()
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    private class Stub : HttpTransport {
        override suspend fun open(request: HttpRequest): HttpResponse = error("not used")
    }
}
