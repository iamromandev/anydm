package dev.anydm.desktop

import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.JobSpec
import dev.anydm.engine.http.Reasons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real environment, with links that fail before any connection is made: nothing here reaches the network. */
class DownloadEnvTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun job(
        id: String,
        url: String,
    ) = JobSpec(id, url, tmp.root.path, "a$id.bin", 0, null, null)

    @Test
    fun theRealEnvFailsEachBadLinkAloneAndKeepsWorkingEvenOnAScopeWithoutASupervisor() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val env = DownloadEnv.real(scope)
            val events = CopyOnWriteArrayList<EngineEvent>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { env.engine.events.collect { events.add(it) } }
            env.engine.start(job("1", "ftp://x.test/a")) // not http(s)
            env.engine.start(job("2", "http://x test/a")) // not a URI
            env.engine.start(job("3", "https://x.test/a b")) // a space: not a valid URI
            val end = System.currentTimeMillis() + 10_000
            while (events.size < 3 && System.currentTimeMillis() < end) Thread.sleep(10)
            assertEquals(listOf("1", "2", "3"), events.map { it.id }.sorted())
            assertTrue(events.all { it is EngineEvent.Failed && it.reason == Reasons.NETWORK }, "$events")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theRealEnvBuildsItsOwnScopeWhenNoneIsGiven() {
        val env = DownloadEnv.real()
        env.engine.setRate(null)
        env.engine.stop("nothing")
    }
}
