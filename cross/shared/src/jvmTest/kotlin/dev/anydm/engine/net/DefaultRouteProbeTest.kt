package dev.anydm.engine.net

import io.kotest.matchers.shouldBe
import java.net.InetAddress
import kotlin.test.Test

class DefaultRouteProbeTest {
    @Test
    fun aPublicLocalAddressIsOnline() {
        DefaultRouteProbe { InetAddress.getByName("192.168.1.5") }.isOnline() shouldBe true
    }

    @Test
    fun theIpv6TargetIsTriedWhenIpv4Fails() {
        val asked = mutableListOf<String>()
        val probe =
            DefaultRouteProbe { target ->
                asked += target
                if (target == "1.1.1.1") null else InetAddress.getByName("2001:db8::5")
            }

        probe.isOnline() shouldBe true
        asked shouldBe listOf("1.1.1.1", "2606:4700:4700::1111")
    }

    @Test
    fun loopbackAnyLocalNullAndThrowingAreOffline() {
        DefaultRouteProbe { InetAddress.getByName("127.0.0.1") }.isOnline() shouldBe false
        DefaultRouteProbe { InetAddress.getByName("0.0.0.0") }.isOnline() shouldBe false
        DefaultRouteProbe { null }.isOnline() shouldBe false
        DefaultRouteProbe { error("no route") }.isOnline() shouldBe false
    }

    @Test
    fun theRealConnectToLoopbackIsNotOnline() {
        defaultRouteAddress("127.0.0.1")?.isLoopbackAddress shouldBe true
        DefaultRouteProbe { defaultRouteAddress("127.0.0.1") }.isOnline() shouldBe false
    }

    @Test
    fun theRealProbeNeverThrows() {
        DefaultRouteProbe().isOnline()
    }
}
