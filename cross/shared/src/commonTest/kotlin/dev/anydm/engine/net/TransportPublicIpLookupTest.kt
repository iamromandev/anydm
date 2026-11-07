package dev.anydm.engine.net

import dev.anydm.engine.http.FakeTransport
import dev.anydm.engine.http.bytesBody
import dev.anydm.engine.http.response
import dev.anydm.engine.platform.network
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The real lookup: the address an outside server sees, asked through the network checked (#635). */
class TransportPublicIpLookupTest {
    private val wifi = network("en0")

    @Test
    fun asksThroughTheNetworkAndReturnsTheTrimmedAddress() =
        runTest {
            var boundTo: String? = null
            val transport =
                FakeTransport {
                    boundTo = currentBoundNetwork()?.key
                    response(200, body = bytesBody("203.0.113.7\n".encodeToByteArray()))
                }
            TransportPublicIpLookup(transport, "https://ip.example/").lookup(wifi) shouldBe "203.0.113.7"
            boundTo shouldBe "en0"
            transport.requests.single().url shouldBe "https://ip.example/"
        }

    @Test
    fun anErrorStatusIsAFailure() =
        runTest {
            val transport = FakeTransport { response(503) }
            shouldThrow<IllegalStateException> { TransportPublicIpLookup(transport, "https://ip.example/").lookup(wifi) }
        }

    @Test
    fun textThatIsNotAnAddressIsAFailure() =
        runTest {
            val transport = FakeTransport { response(200, body = bytesBody("<html>captive portal</html>".encodeToByteArray())) }
            shouldThrow<IllegalStateException> { TransportPublicIpLookup(transport, "https://ip.example/").lookup(wifi) }
        }

    @Test
    fun anIpv6AddressIsAccepted() =
        runTest {
            val transport = FakeTransport { response(200, body = bytesBody("2001:db8::1".encodeToByteArray())) }
            TransportPublicIpLookup(transport, "https://ip.example/").lookup(wifi) shouldBe "2001:db8::1"
        }

    private suspend fun lookup(text: String) =
        TransportPublicIpLookup(
            FakeTransport { response(200, body = bytesBody(text.encodeToByteArray())) },
            "https://ip.example/",
        ).lookup(wifi)

    @Test
    fun badAddressesAreRejected() =
        runTest {
            val bad =
                listOf(
                    "256.1.1.1",
                    "1.2.3",
                    "1.2.3.4.5",
                    "1..2.3",
                    "1.2.3.-4",
                    "\u0661.2.3.4",
                    "1.2.3.4a",
                    ":",
                    "::::",
                    "1:2",
                    "1::2::3",
                    "12345::1",
                    "g::1",
                    "1:2:3:4:5:6:7:8:9",
                    "1:2:3:4:5:6:7",
                    ":1:2:3:4:5:6:7",
                    "1.2.3.4::",
                    "::1.2.3",
                    "1::\u0661",
                )
            for (text in bad) {
                shouldThrow<IllegalStateException> { lookup(text) }
            }
        }

    @Test
    fun addressesAreReturnedInCanonicalForm() =
        runTest {
            lookup("[2001:DB8::A]") shouldBe "2001:db8::a"
            lookup("fe80::1%en0") shouldBe "fe80::1"
            lookup("::ffff:1.2.3.4") shouldBe "::ffff:1.2.3.4"
            lookup("1:2:3:4:5:6:7:8") shouldBe "1:2:3:4:5:6:7:8"
            lookup("0.0.0.255") shouldBe "0.0.0.255"
        }
}
