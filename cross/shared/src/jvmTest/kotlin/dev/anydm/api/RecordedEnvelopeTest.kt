package dev.anydm.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecordedEnvelopeTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/$name")) { "no fixture $name" }.readText()

    @Test
    fun `the API's unsupported link is the error that makes a direct download`() {
        val error = assertFailsWith<ApiException> { unwrap(400, fixture("error_unsupported.json")) }
        assertEquals("unsupported_url", error.type)
        assertEquals(400, error.code)
    }

    @Test
    fun `an unreachable site keeps the API's own message`() {
        val error = assertFailsWith<ApiException> { unwrap(502, fixture("error_extract.json")) }
        assertEquals("external_api_error", error.type)
        assertEquals(true, error.message?.startsWith("Extraction failed"))
    }
}
