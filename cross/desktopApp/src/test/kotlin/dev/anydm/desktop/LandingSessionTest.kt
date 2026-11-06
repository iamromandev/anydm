package dev.anydm.desktop

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class LandingSessionTest {
    private val a = "https://example.com/a.zip"
    private val b = "https://example.com/b.zip"
    private val magnet = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"

    private fun sessions(block: suspend TestScope.(FakeSource, LandingSession) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            val looks = MutableStateFlow(NetworkLooks())
            val session =
                LandingSession(
                    backgroundScope,
                ) { prefill, scope -> NewDownloadModel(source, looks, scope, "", "/home/me", prefill = prefill) }
            block(source, session)
        }

    private val LandingSession.url get() = model.value.model.state.value.url

    @Test
    fun `it starts with a blank model`() =
        sessions { _, session ->
            assertEquals("", session.url)
        }

    @Test
    fun `a link fills the field`() =
        sessions { _, session ->
            session.open(listOf(a))
            assertEquals(a, session.url)
        }

    @Test
    fun `what is no link is left out`() =
        sessions { _, session ->
            session.open(listOf("just words", "/home/me/readme.txt"))
            assertEquals("", session.url)
            session.open(listOf("just words", a))
            assertEquals(a, session.url)
        }

    @Test
    fun `of several links the first is taken and the rest dropped`() =
        sessions { _, session ->
            session.open(listOf(a, b, magnet))
            assertEquals(a, session.url)
        }

    @Test
    fun `no links changes nothing`() =
        sessions { _, session ->
            session.open(listOf(a))
            session.open(emptyList())
            assertEquals(a, session.url)
        }

    @Test
    fun `the same link again keeps the model as it is`() =
        sessions { _, session ->
            session.open(listOf(a))
            val entry = session.model.value
            session.open(listOf(" $a "))
            assertSame(entry, session.model.value)
            assertEquals(a, session.url)
        }

    @Test
    fun `another link replaces the one in the field`() =
        sessions { _, session ->
            session.open(listOf(a))
            session.open(listOf(b))
            assertEquals(b, session.url)
        }

    @Test
    fun `reset gives a blank model`() =
        sessions { _, session ->
            session.open(listOf(a))
            val before = session.model.value
            session.reset()
            assertNotEquals(before.id, session.model.value.id)
            assertEquals("", session.url)
            assertFalse(before.scope.isActive)
        }

    @Test
    fun `reset does not fill the new model from the clipboard`() =
        sessions { source, _ ->
            source.clipboardText = a
            val looks = MutableStateFlow(NetworkLooks())
            val made =
                LandingSession(
                    backgroundScope,
                ) { prefill, scope -> NewDownloadModel(source, looks, scope, "", "/home/me", prefill = prefill) }
            // The clipboard is read off the test thread.
            val until = System.nanoTime() + 5_000_000_000
            while (made.url.isEmpty() && System.nanoTime() < until) Thread.sleep(10)
            assertEquals(a, made.url)
            made.reset()
            Thread.sleep(200)
            assertEquals("", made.url)
        }

    @Test
    fun `a dropped torrent fills the field and other files do not`() =
        sessions { _, session ->
            assertFalse(session.dropped(listOf("/home/me/notes.txt")))
            assertEquals("", session.url)
            assertTrue(session.dropped(listOf("/home/me/notes.txt", "/home/me/a.torrent")))
            assertEquals("/home/me/a.torrent", session.url)
        }
}
