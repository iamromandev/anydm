package dev.anydm.desktop

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class NewDownloadsTest {
    private val a = "https://example.com/a.zip"
    private val b = "https://example.com/b.zip"
    private val magnet = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"

    private fun hosts(block: suspend TestScope.(FakeSource, NewDownloads) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            val looks = MutableStateFlow(NetworkLooks())
            val host = NewDownloads(backgroundScope) { initial, scope -> NewDownloadModel(source, looks, scope, initial, "/home/me") }
            block(source, host)
        }

    private val NewDownloads.urls get() = state.value.entries.map { it.model.state.value.url }

    @Test
    fun `no links opens one blank dialog, and asking again does not open a second`() =
        hosts { _, host ->
            host.open(emptyList())
            host.open(emptyList())
            assertEquals(listOf(""), host.urls)
        }

    @Test
    fun `a link opens a dialog filled in with it`() =
        hosts { _, host ->
            host.open(listOf(a))
            assertEquals(listOf(a), host.urls)
        }

    @Test
    fun `several links stack, the first on show`() =
        hosts { _, host ->
            host.open(listOf(a, b, magnet))
            assertEquals(listOf(a, b, magnet), host.urls)
            assertEquals(0, host.state.value.shown)
            host.show(2)
            assertEquals(
                magnet,
                host.state.value.current!!
                    .model.state.value.url,
            )
            host.show(9)
            assertEquals(2, host.state.value.shown)
        }

    @Test
    fun `what is no link is left out, and nothing opens when nothing is a link`() =
        hosts { _, host ->
            host.open(listOf("just words", "/home/me/readme.txt"))
            assertTrue(
                host.state.value.entries
                    .isEmpty(),
            )
            host.open(listOf("just words", a))
            assertEquals(listOf(a), host.urls)
        }

    @Test
    fun `a link already open is not opened again`() =
        hosts { _, host ->
            host.open(listOf(a))
            host.open(listOf(a, b))
            assertEquals(listOf(a, b), host.urls)
        }

    @Test
    fun `a blank dialog nobody typed in takes the first link that comes`() =
        hosts { _, host ->
            host.open(emptyList())
            host.open(listOf(a, b))
            assertEquals(listOf(a, b), host.urls)
        }

    @Test
    fun `a dialog that has a link in it is kept when another link comes`() =
        hosts { _, host ->
            host.open(listOf(a))
            host.open(listOf(b))
            assertEquals(listOf(a, b), host.urls)
        }

    @Test
    fun `closing the shown dialog brings the next one forward`() =
        hosts { _, host ->
            host.open(listOf(a, b))
            val first = host.state.value.entries[0]
            host.close(first.id)
            assertEquals(listOf(b), host.urls)
            assertEquals(0, host.state.value.shown)
            host.close(
                host.state.value.entries[0]
                    .id,
            )
            assertTrue(
                host.state.value.entries
                    .isEmpty(),
            )
            assertNull(host.state.value.current)
        }

    @Test
    fun `closing the last of several shows the one before it`() =
        hosts { _, host ->
            host.open(listOf(a, b))
            host.show(1)
            host.close(
                host.state.value.entries[1]
                    .id,
            )
            assertEquals(listOf(a), host.urls)
            assertEquals(
                a,
                host.state.value.current!!
                    .model.state.value.url,
            )
        }

    @Test
    fun `closing a dialog behind the shown one keeps the shown one shown`() =
        hosts { _, host ->
            host.open(listOf(a, b, magnet))
            host.show(2)
            host.close(
                host.state.value.entries[0]
                    .id,
            )
            assertEquals(
                magnet,
                host.state.value.current!!
                    .model.state.value.url,
            )
        }

    @Test
    fun `a dialog can be opened again after it was closed`() =
        hosts { _, host ->
            host.open(listOf(a))
            host.close(
                host.state.value.entries[0]
                    .id,
            )
            host.open(listOf(a))
            assertNotNull(host.state.value.current)
            assertEquals(listOf(a), host.urls)
        }
}
