package dev.anydm.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class EnvelopeTest {
    @Test
    fun `a success envelope hands back its data and meta`() {
        val envelope = unwrap(200, """{"status":"success","code":200,"data":{"all":2},"meta":{"page":1,"total_pages":3}}""")
        assertEquals(3, envelope.meta?.totalPages)
        assertEquals("""{"all":2}""", envelope.data.toString())
    }

    @Test
    fun `an error envelope carries the service's own message and type`() {
        val error =
            assertFailsWith<ApiException> {
                unwrap(422, """{"status":"error","code":422,"type":"unsupported_url","message":"No site supports this link"}""")
            }
        assertEquals("No site supports this link", error.message)
        assertEquals("unsupported_url", error.type)
        assertEquals(422, error.code)
    }

    @Test
    fun `the older success-flag shape is read too`() {
        assertEquals("1", unwrap(200, """{"success":true,"data":1}""").data.toString())
        assertEquals("nope", assertFailsWith<ApiException> { unwrap(400, """{"success":false,"error":"nope"}""") }.message)
    }

    @Test
    fun `a 401 is its own kind of failure`() {
        assertIs<Unauthorized>(assertFailsWith<ApiException> { unwrap(401, """{"status":"error","code":401,"message":"bad key"}""") })
    }

    @Test
    fun `no body is fine on a 204, and anything unreadable says its status`() {
        assertNull(unwrap(204, "").data)
        assertEquals("Request failed (HTTP 502)", assertFailsWith<ApiException> { unwrap(502, "<html>bad gateway</html>") }.message)
    }
}
