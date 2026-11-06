package dev.anydm.desktop

import dev.anydm.engine.http.HttpIoException
import dev.anydm.engine.http.HttpIoKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

private val installer = ByteArray(100_000) { (it % 251).toByte() }

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private val installerSha = sha256(installer)

private fun assetUrl(
    tag: String,
    name: String,
) = "https://github.com/iamromandev/anydm/releases/download/$tag/$name"

private fun releaseOf(
    version: String = "1.2.0",
    name: String = "anydm-$version.dmg",
    url: String = assetUrl("v$version", name),
    size: Long? = installer.size.toLong(),
    sha: String = installerSha,
) = AppRelease(
    Version.parse(version)!!,
    "v$version",
    "https://github.com/iamromandev/anydm/releases/tag/v$version",
    AppAsset(name, url, size, sha),
)

/** [AppReleaseSource] that answers [result] (or throws [failure]) and counts the questions. */
private class FakeReleases(
    var result: ReleaseCheck = ReleaseCheck.None,
    var failure: Throwable? = null,
) : AppReleaseSource {
    var calls = 0

    /** When set, a question waits here. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun latest(): ReleaseCheck {
        calls++
        gate?.await()
        failure?.let { throw it }
        return result
    }
}

/** [UpdateFiles] in memory: what is written, made and deleted is recorded; nothing touches the disk. */
private class FakeUpdateFiles : UpdateFiles {
    /** When set, hashing a file waits here. */
    var shaGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** When set, making a folder throws it. */
    var createFailure: Throwable? = null

    /** When set, writing to a file throws it. */
    var writeFailure: Throwable? = null
    var sweeps = 0

    /** When set, deleting a folder waits here. */
    var deleteGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val stored = mutableMapOf<Path, ByteArray>()
    val directories = mutableListOf<Path>()
    val deleted = mutableListOf<Path>()
    val openSinks = mutableListOf<Path>()

    override suspend fun createDirectory(): Path {
        createFailure?.let { throw it }
        return Path.of("/fake/update-${directories.size + 1}").also { directories.add(it) }
    }

    override suspend fun sweepOld() {
        sweeps++
    }

    override suspend fun create(
        directory: Path,
        name: String,
    ): UpdateSink {
        check(directory in directories) { "$directory was never made" }
        val path = directory.resolve(name)
        check(path.normalize().parent == directory) { "$name leaves $directory" }
        stored[path] = ByteArray(0)
        openSinks.add(path)
        return object : UpdateSink {
            override suspend fun write(
                bytes: ByteArray,
                length: Int,
            ) {
                writeFailure?.let { throw it }
                stored[path] = stored.getValue(path) + bytes.copyOf(length)
            }

            override suspend fun close() {
                openSinks.remove(path)
            }
        }
    }

    override suspend fun sha256(file: Path): String? {
        shaGate?.await()
        return stored[file]?.let(::sha256)
    }

    override suspend fun deleteAll(directory: Path) {
        deleteGate?.await()
        deleted.add(directory)
        stored.keys.removeAll { it.startsWith(directory) }
    }

    val files get() = stored.keys.toList()
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Rig(
    val test: TestScope,
    current: String = "1.0.0",
    autoUpdate: Boolean = true,
    val releases: FakeReleases = FakeReleases(),
    val http: FakeHttp = FakeHttp(),
    val prefs: MemoryViewPrefs = MemoryViewPrefs(autoUpdate = autoUpdate),
    val files: FakeUpdateFiles = FakeUpdateFiles(),
    val opener: FakeFileActions = FakeFileActions(),
    maxBytes: Long = 1L shl 30,
) {
    var active = 0
    var quits = 0

    /** When set, `quit` throws it (after counting). */
    var quitFailure: Throwable? = null
    val events = mutableListOf<UpdateEvent>()
    val updater =
        Updater(
            scope = test.backgroundScope,
            current = Version.parse(current)!!,
            releases = releases,
            transport = http,
            files = files,
            opener = opener,
            prefs = prefs,
            activeDownloads = { active },
            quit = {
                quits++
                quitFailure?.let { throw it }
            },
            installerExtension = ".dmg",
            maxDownloadBytes = maxBytes,
        )

    val state get() = updater.state.value

    init {
        test.backgroundScope.launch(StandardTestDispatcher(test.testScheduler), start = CoroutineStart.UNDISPATCHED) {
            updater.events.collect { events += it }
        }
    }

    /** Serves [release]'s installer ([bytes]) at its address. */
    fun serve(
        release: AppRelease,
        bytes: ByteArray = installer,
        finalUrl: String? = null,
        status: Int = 200,
        withLength: Boolean = true,
        failAfter: Int = Int.MAX_VALUE,
        onRead: suspend (Int) -> Unit = {},
    ) {
        http.routes[release.asset.url] =
            Answer(
                status = status,
                body = { FakeBody(bytes, failAfter, onRead) },
                finalUrl = finalUrl,
                headers = if (withLength) listOf("Content-Length" to bytes.size.toString()) else emptyList(),
            )
    }

    fun found(release: AppRelease) {
        releases.result = ReleaseCheck.Found(release)
        serve(release)
    }

    suspend fun settle() = test.testScheduler.runCurrent()
}

internal class UpdaterTest {
    private fun runRig(
        current: String = "1.0.0",
        autoUpdate: Boolean = true,
        maxBytes: Long = 1L shl 30,
        block: suspend TestScope.(Rig) -> Unit,
    ) = runTest {
        block(Rig(this, current, autoUpdate, maxBytes = maxBytes))
    }

    @Test
    fun `it starts idle and checks nothing until started`() =
        runRig { rig ->
            runCurrent()
            assertEquals(UpdateState.Idle, rig.state)
            assertEquals(0, rig.releases.calls)
        }

    @Test
    fun `a newer release found at launch is available and not downloaded while automatic updates are off`() =
        runRig(autoUpdate = false) { rig ->
            val release = releaseOf("1.2.0")
            rig.found(release)

            rig.updater.start()
            runCurrent()

            assertEquals(UpdateState.Available(release.version, release.pageUrl), rig.state)
            assertTrue(rig.http.requests.isEmpty())
            assertTrue(rig.files.directories.isEmpty())
        }

    @Test
    fun `with automatic updates on a newer release is downloaded, verified and ready`() =
        runRig { rig ->
            val release = releaseOf("1.2.0")
            rig.found(release)

            rig.updater.start()
            runCurrent()

            val ready = assertIs<UpdateState.Ready>(rig.state)
            assertEquals(release.version, ready.version)
            assertEquals(Path.of("/fake/update-1/anydm-1.2.0.dmg"), ready.file)
            assertTrue(installer.contentEquals(rig.files.stored.getValue(ready.file)))
            assertEquals(listOf(release.asset.url), rig.http.requests.map { it.url })
            assertTrue(rig.files.openSinks.isEmpty())
            assertTrue(rig.http.bodies.all { it.closed })
            assertTrue(rig.opener.calls.isEmpty())
            assertEquals(0, rig.quits)
        }

    @Test
    fun `the percent rises while downloading`() =
        runRig { rig ->
            val release = releaseOf("1.2.0")
            val seen = mutableListOf<Int>()
            rig.releases.result = ReleaseCheck.Found(release)
            rig.serve(release) { seen += (rig.state as? UpdateState.Downloading)?.percent ?: -1 }

            rig.updater.start()
            runCurrent()

            assertTrue(seen.size > 2, "$seen")
            assertTrue(seen.all { it in 0..99 }, "$seen")
            assertEquals(seen.sorted(), seen)
            assertTrue(seen.last() > seen.first(), "$seen")
            assertIs<UpdateState.Ready>(rig.state)
        }

    @Test
    fun `a check shows the checking state meanwhile`() =
        runRig { rig ->
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            rig.releases.gate = gate

            rig.updater.start()
            runCurrent()
            assertEquals(UpdateState.Checking, rig.state)

            gate.complete(Unit)
            runCurrent()
            assertEquals(UpdateState.Idle, rig.state)
        }

    @Test
    fun `the same or an older release is not an update`() =
        runRig(current = "1.2.0") { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            assertEquals(UpdateState.Idle, rig.state)

            rig.found(releaseOf("1.1.9"))
            rig.updater.checkForUpdates()
            runCurrent()
            assertEquals(UpdateState.Idle, rig.state)
            assertTrue(rig.http.requests.isEmpty())
        }

    @Test
    fun `a pre-release suffix on the running version counts as older than its release`() =
        runRig(current = "1.2.0-rc.1", autoUpdate = false) { rig ->
            val release = releaseOf("1.2.0")
            rig.found(release)

            rig.updater.start()
            runCurrent()

            assertIs<UpdateState.Available>(rig.state)
        }

    @Test
    fun `it checks every four hours after the launch check`() =
        runRig { rig ->
            rig.updater.start()
            runCurrent()
            assertEquals(1, rig.releases.calls)

            advanceTimeBy(4.hours - 1.milliseconds)
            runCurrent()
            assertEquals(1, rig.releases.calls)

            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(2, rig.releases.calls)

            advanceTimeBy(4.hours)
            runCurrent()
            assertEquals(3, rig.releases.calls)
        }

    @Test
    fun `starting twice runs one schedule`() =
        runRig { rig ->
            rig.updater.start()
            rig.updater.start()
            runCurrent()
            advanceTimeBy(4.hours)
            runCurrent()

            assertEquals(2, rig.releases.calls)
        }

    @Test
    fun `a failed timer check is not retried before the next tick and says nothing`() =
        runRig { rig ->
            rig.releases.result = ReleaseCheck.Failed
            rig.updater.start()
            runCurrent()
            assertEquals(1, rig.releases.calls)

            advanceTimeBy(4.hours - 1.milliseconds)
            runCurrent()
            assertEquals(1, rig.releases.calls)
            assertEquals(UpdateState.Idle, rig.state)
            assertTrue(rig.events.isEmpty())

            rig.releases.result = ReleaseCheck.None
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(2, rig.releases.calls)
            assertTrue(rig.events.isEmpty())
        }

    @Test
    fun `a source that throws is a failed check`() =
        runRig { rig ->
            rig.releases.failure = IllegalStateException("boom")
            rig.updater.start()
            runCurrent()
            assertEquals(UpdateState.Idle, rig.state)

            rig.updater.checkForUpdates()
            runCurrent()
            assertEquals<List<UpdateEvent>>(listOf(UpdateEvent.CheckFailed), rig.events)
        }

    @Test
    fun `a manual check with nothing newer says so once`() =
        runRig { rig ->
            rig.releases.result = ReleaseCheck.None

            rig.updater.checkForUpdates()
            runCurrent()

            assertEquals<List<UpdateEvent>>(listOf(UpdateEvent.UpToDate), rig.events)
            assertEquals(UpdateState.Idle, rig.state)
        }

    @Test
    fun `a manual check of the same version says it is up to date`() =
        runRig(current = "1.2.0") { rig ->
            rig.found(releaseOf("1.2.0"))

            rig.updater.checkForUpdates()
            runCurrent()

            assertEquals<List<UpdateEvent>>(listOf(UpdateEvent.UpToDate), rig.events)
        }

    @Test
    fun `a manual check that fails says so and leaves the state`() =
        runRig(autoUpdate = false) { rig ->
            val release = releaseOf("1.2.0")
            rig.found(release)
            rig.updater.checkForUpdates()
            runCurrent()
            val before = rig.state
            assertIs<UpdateState.Available>(before)

            rig.releases.result = ReleaseCheck.Failed
            rig.updater.checkForUpdates()
            runCurrent()

            assertEquals<List<UpdateEvent>>(listOf(UpdateEvent.CheckFailed), rig.events)
            assertEquals(before, rig.state)
        }

    @Test
    fun `a manual check that finds an update shows no dialog event`() =
        runRig(autoUpdate = false) { rig ->
            rig.found(releaseOf("1.2.0"))

            rig.updater.checkForUpdates()
            runCurrent()

            assertTrue(rig.events.isEmpty())
            assertIs<UpdateState.Available>(rig.state)
        }

    @Test
    fun `timer checks emit no events whatever they find`() =
        runRig { rig ->
            rig.releases.result = ReleaseCheck.None
            rig.updater.start()
            runCurrent()
            rig.releases.result = ReleaseCheck.Failed
            advanceTimeBy(4.hours)
            runCurrent()
            rig.found(releaseOf("1.0.0"))
            advanceTimeBy(4.hours)
            runCurrent()

            assertEquals(3, rig.releases.calls)
            assertTrue(rig.events.isEmpty())
        }

    @Test
    fun `a manual check while a check runs is ignored`() =
        runRig { rig ->
            rig.releases.result = ReleaseCheck.None
            rig.updater.checkForUpdates()
            rig.updater.checkForUpdates()
            runCurrent()

            assertEquals(1, rig.releases.calls)
            assertEquals<List<UpdateEvent>>(listOf(UpdateEvent.UpToDate), rig.events)
        }

    @Test
    fun `a manual check during a download is ignored`() =
        runRig { rig ->
            val release = releaseOf("1.2.0")
            var during: UpdateState? = null
            rig.releases.result = ReleaseCheck.Found(release)
            rig.serve(release) {
                if (during == null) {
                    during = rig.state
                    rig.updater.checkForUpdates()
                }
            }

            rig.updater.start()
            runCurrent()

            assertIs<UpdateState.Downloading>(during)
            assertEquals(1, rig.releases.calls)
            assertTrue(rig.events.isEmpty())
            assertIs<UpdateState.Ready>(rig.state)
        }

    @Test
    fun `a newer release that cannot be installed is an error with its page`() =
        runRig { rig ->
            val page = "https://github.com/iamromandev/anydm/releases/tag/v1.2.0"
            rig.releases.result =
                ReleaseCheck.CannotInstall(
                    Version.parse("1.2.0")!!,
                    "v1.2.0",
                    page,
                    "This release has no checksums, so it can't be verified.",
                )

            rig.updater.start()
            runCurrent()

            val error = assertIs<UpdateState.Error>(rig.state)
            assertEquals(Version.parse("1.2.0"), error.version)
            assertEquals(page, error.pageUrl)
            assertEquals("This release has no checksums, so it can't be verified.", error.reason)
            assertTrue(rig.http.requests.isEmpty())
            assertTrue(rig.files.directories.isEmpty())
            assertTrue(rig.events.isEmpty())
        }

    @Test
    fun `a release that cannot be installed is no error when it is not newer`() =
        runRig(current = "1.2.0") { rig ->
            rig.releases.result = ReleaseCheck.CannotInstall(Version.parse("1.2.0")!!, "v1.2.0", "https://github.com/x", "no sums")

            rig.updater.start()
            runCurrent()

            assertEquals(UpdateState.Idle, rig.state)
        }

    @Test
    fun `a checksum mismatch ends in an error with nothing left behind`() =
        runRig { rig ->
            val release = releaseOf("1.2.0", sha = "0".repeat(64))
            rig.found(release)

            rig.updater.start()
            runCurrent()

            val error = assertIs<UpdateState.Error>(rig.state)
            assertEquals(release.version, error.version)
            assertEquals(release.pageUrl, error.pageUrl)
            assertTrue(error.reason.contains("checksum"), error.reason)
            assertEquals(listOf(Path.of("/fake/update-1")), rig.files.deleted)
            assertTrue(rig.files.files.isEmpty())
            assertTrue(rig.opener.calls.isEmpty())
            assertEquals(0, rig.quits)
        }

    @Test
    fun `an uppercase checksum still matches`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0", sha = installerSha.uppercase()))

            rig.updater.start()
            runCurrent()

            assertIs<UpdateState.Ready>(rig.state)
        }

    @Test
    fun `a size over the cap in the release is refused before any request`() =
        runRig(maxBytes = 50_000) { rig ->
            rig.found(releaseOf("1.2.0"))

            rig.updater.start()
            runCurrent()

            assertIs<UpdateState.Error>(rig.state)
            assertTrue(rig.http.requests.isEmpty())
            assertTrue(rig.files.directories.isEmpty())
        }

    @Test
    fun `a content length over the cap is refused before anything is written`() =
        runRig(maxBytes = 50_000) { rig ->
            val release = releaseOf("1.2.0", size = null)
            rig.found(release)

            rig.updater.start()
            runCurrent()

            assertIs<UpdateState.Error>(rig.state)
            assertTrue(rig.files.directories.isEmpty())
            assertTrue(rig.http.bodies.all { it.closed })
        }

    @Test
    fun `a body that grows past the cap without announcing it is stopped and removed`() =
        runRig(maxBytes = 50_000) { rig ->
            val release = releaseOf("1.2.0", size = null)
            rig.releases.result = ReleaseCheck.Found(release)
            rig.serve(release, withLength = false)

            rig.updater.start()
            runCurrent()

            assertIs<UpdateState.Error>(rig.state)
            assertEquals(listOf(Path.of("/fake/update-1")), rig.files.deleted)
            assertTrue(rig.files.files.isEmpty())
            assertTrue(rig.http.bodies.all { it.closed })
            assertTrue(rig.files.openSinks.isEmpty())
        }

    @Test
    fun `a download shorter than the release says is an error`() =
        runRig { rig ->
            val release = releaseOf("1.2.0", sha = sha256(installer.copyOf(60_000)))
            rig.releases.result = ReleaseCheck.Found(release)
            rig.serve(release, bytes = installer.copyOf(60_000), withLength = false)

            rig.updater.start()
            runCurrent()

            assertIs<UpdateState.Error>(rig.state)
            assertTrue(rig.files.files.isEmpty())
        }

    @Test
    fun `a connection that breaks mid download is an error and cleans up`() =
        runRig { rig ->
            val release = releaseOf("1.2.0")
            rig.releases.result = ReleaseCheck.Found(release)
            rig.serve(release, failAfter = 30_000)

            rig.updater.start()
            runCurrent()

            val error = assertIs<UpdateState.Error>(rig.state)
            assertEquals(release.version, error.version)
            assertEquals(listOf(Path.of("/fake/update-1")), rig.files.deleted)
            assertTrue(rig.files.openSinks.isEmpty())
        }

    @Test
    fun `no connection is an error`() =
        runRig { rig ->
            val release = releaseOf("1.2.0")
            rig.releases.result = ReleaseCheck.Found(release)
            val updater =
                Updater(
                    backgroundScope,
                    Version.parse("1.0.0")!!,
                    rig.releases,
                    object : dev.anydm.engine.http.HttpTransport {
                        override suspend fun open(request: dev.anydm.engine.http.HttpRequest) =
                            throw HttpIoException(HttpIoKind.CONNECT, "github.com")
                    },
                    rig.files,
                    rig.opener,
                    MemoryViewPrefs(),
                    { 0 },
                    {},
                    ".dmg",
                )

            updater.start()
            runCurrent()

            assertIs<UpdateState.Error>(updater.state.value)
            assertTrue(rig.files.files.isEmpty())
        }

    @Test
    fun `a failing status is an error`() =
        runRig { rig ->
            val release = releaseOf("1.2.0")
            rig.releases.result = ReleaseCheck.Found(release)
            rig.serve(release, status = 404)

            rig.updater.start()
            runCurrent()

            assertIs<UpdateState.Error>(rig.state)
            assertTrue(rig.files.files.isEmpty())
        }

    @Test
    fun `an asset address off the anydm release is never requested`() =
        runTest {
            val bad =
                listOf(
                    "https://evil.example/iamromandev/anydm/releases/download/v1.2.0/anydm-1.2.0.dmg",
                    "http://github.com/iamromandev/anydm/releases/download/v1.2.0/anydm-1.2.0.dmg",
                    "https://github.com/other/repo/releases/download/v1.2.0/anydm-1.2.0.dmg",
                    "https://github.com/iamromandev/anydm/releases/download/v1.1.0/anydm-1.2.0.dmg",
                    "https://github.com/iamromandev/anydm/releases/download/v1.2.0/../../../evil.dmg",
                    "https://github.com@evil.example/iamromandev/anydm/releases/download/v1.2.0/anydm-1.2.0.dmg",
                )
            for (url in bad) {
                val rig = Rig(this)
                rig.found(releaseOf("1.2.0", url = url))
                rig.updater.start()
                runCurrent()

                assertIs<UpdateState.Error>(rig.state, url)
                assertTrue(rig.http.requests.isEmpty(), url)
                assertTrue(rig.files.directories.isEmpty(), url)
            }
        }

    @Test
    fun `a download redirected off github or to plain http is refused and removed`() =
        runTest {
            val finals =
                listOf(
                    "https://evil.example/anydm.dmg",
                    "http://objects.githubusercontent.com/anydm.dmg",
                    "https://githubusercontent.com.evil.example/anydm.dmg",
                    "https://evilgithub.com/anydm.dmg",
                )
            for (final in finals) {
                val rig = Rig(this)
                val release = releaseOf("1.2.0")
                rig.releases.result = ReleaseCheck.Found(release)
                rig.serve(release, finalUrl = final)

                rig.updater.start()
                runCurrent()

                assertIs<UpdateState.Error>(rig.state, final)
                assertTrue(rig.files.directories.isEmpty(), final)
                assertTrue(rig.http.bodies.all { it.closed }, final)
            }
        }

    @Test
    fun `a download redirected to githubusercontent is accepted`() =
        runRig { rig ->
            val release = releaseOf("1.2.0")
            rig.releases.result = ReleaseCheck.Found(release)
            rig.serve(release, finalUrl = "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?x=y")

            rig.updater.start()
            runCurrent()

            assertIs<UpdateState.Ready>(rig.state)
        }

    @Test
    fun `an asset name with path parts is refused before any request`() =
        runTest {
            for (name in listOf("../../etc/evil.dmg", "a/b.dmg", "..\\evil.dmg")) {
                val rig = Rig(this)
                rig.found(releaseOf("1.2.0", name = name, url = assetUrl("v1.2.0", name)))
                rig.updater.start()
                runCurrent()

                assertIs<UpdateState.Error>(rig.state, name)
                assertTrue(rig.http.requests.isEmpty(), name)
                assertTrue(rig.files.directories.isEmpty(), name)
            }
        }

    @Test
    fun `the installer is stored under a plain file name`() {
        assertEquals("anydm-1.2.0.dmg", sanitizedInstallerName("anydm-1.2.0.dmg"))
        assertEquals("evil.dmg", sanitizedInstallerName("../../etc/evil.dmg"))
        assertEquals("evil.dmg", sanitizedInstallerName("..\\..\\evil.dmg"))
        assertEquals("a_b.dmg", sanitizedInstallerName("a b.dmg"))
        assertEquals("a_b_.dmg", sanitizedInstallerName("a\u0000b\n.dmg"))
        assertEquals("hidden.dmg", sanitizedInstallerName(".hidden.dmg"))
        assertEquals("anydm-update", sanitizedInstallerName(".."))
        assertEquals("anydm-update", sanitizedInstallerName("/"))
        assertEquals("anydm-update", sanitizedInstallerName(""))
        assertTrue(sanitizedInstallerName("a".repeat(500) + ".dmg").length <= 100)
        assertTrue(sanitizedInstallerName("a".repeat(500) + ".dmg").endsWith(".dmg"))
    }

    @Test
    fun `ticking automatic updates on while an update is available starts the download`() =
        runRig(autoUpdate = false) { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            assertIs<UpdateState.Available>(rig.state)

            rig.updater.setAutoUpdate(true)
            runCurrent()

            assertTrue(rig.updater.autoUpdate.value)
            assertTrue(rig.prefs.autoUpdate)
            assertIs<UpdateState.Ready>(rig.state)
        }

    @Test
    fun `automatic updates off is kept and starts nothing`() =
        runRig { rig ->
            rig.updater.setAutoUpdate(false)
            runCurrent()

            assertFalse(rig.updater.autoUpdate.value)
            assertFalse(rig.prefs.autoUpdate)
            assertEquals(UpdateState.Idle, rig.state)
            assertTrue(rig.http.requests.isEmpty())
        }

    @Test
    fun `ticking automatic updates on with nothing available downloads nothing`() =
        runRig(autoUpdate = false) { rig ->
            rig.updater.setAutoUpdate(true)
            runCurrent()

            assertEquals(UpdateState.Idle, rig.state)
            assertTrue(rig.http.requests.isEmpty())
        }

    @Test
    fun `the setting is read from the preferences and survives a new updater`() =
        runRig { rig ->
            val node =
                java.util.prefs.Preferences
                    .userRoot()
                    .node("dev/anydm/desktop-test-${System.nanoTime()}")
            try {
                fun updater() =
                    Updater(
                        backgroundScope,
                        Version.parse("1.0.0")!!,
                        rig.releases,
                        rig.http,
                        rig.files,
                        rig.opener,
                        PreferencesViewPrefs(node),
                        { 0 },
                        {},
                        ".dmg",
                    )

                assertTrue(updater().autoUpdate.value)
                updater().setAutoUpdate(false)
                assertFalse(updater().autoUpdate.value)
                updater().setAutoUpdate(true)
                assertTrue(updater().autoUpdate.value)
            } finally {
                node.removeNode()
            }
        }

    @Test
    fun `download starts from an available update`() =
        runRig(autoUpdate = false) { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()

            rig.updater.download()
            runCurrent()

            assertIs<UpdateState.Ready>(rig.state)
        }

    @Test
    fun `download does nothing when no update is available`() =
        runRig { rig ->
            rig.updater.download()
            runCurrent()

            assertEquals(UpdateState.Idle, rig.state)
            assertTrue(rig.http.requests.isEmpty())
        }

    @Test
    fun `a failed download can be tried again`() =
        runRig(autoUpdate = false) { rig ->
            val release = releaseOf("1.2.0")
            rig.releases.result = ReleaseCheck.Found(release)
            rig.serve(release, failAfter = 1000)
            rig.updater.start()
            runCurrent()
            rig.updater.download()
            runCurrent()
            assertIs<UpdateState.Error>(rig.state)

            rig.serve(release)
            rig.updater.download()
            runCurrent()

            assertIs<UpdateState.Ready>(rig.state)
            assertEquals(Path.of("/fake/update-2"), (rig.state as UpdateState.Ready).file.parent)
        }

    @Test
    fun `a newer release replaces a ready one and removes its file`() =
        runRig(autoUpdate = false) { rig ->
            rig.found(releaseOf("1.1.0"))
            rig.updater.start()
            runCurrent()
            rig.updater.download()
            runCurrent()
            val old = assertIs<UpdateState.Ready>(rig.state)

            rig.found(releaseOf("1.2.0"))
            advanceTimeBy(4.hours)
            runCurrent()

            assertEquals(Version.parse("1.2.0"), (rig.state as UpdateState.Available).version)
            assertFalse(old.file in rig.files.stored)
        }

    @Test
    fun `the same release found again leaves a ready one alone`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            val ready = rig.state

            advanceTimeBy(4.hours)
            runCurrent()

            assertEquals(ready, rig.state)
            assertEquals(1, rig.files.directories.size)
        }

    @Test
    fun `stopping cancels the schedule and removes a half downloaded file`() =
        runRig { rig ->
            val release = releaseOf("1.2.0")
            rig.releases.result = ReleaseCheck.Found(release)
            rig.serve(release) { if (it > 20_000) rig.updater.stop() }
            rig.updater.start()
            runCurrent()

            advanceTimeBy(8.hours)
            runCurrent()

            assertEquals(1, rig.releases.calls)
            assertTrue(rig.files.files.isEmpty())
            assertEquals(listOf(Path.of("/fake/update-1")), rig.files.deleted)
            assertTrue(rig.files.openSinks.isEmpty())
        }

    @Test
    fun `restart before anything is ready does nothing`() =
        runRig { rig ->
            assertEquals(RestartResult.NotReady, rig.updater.restartToUpdate())
            assertTrue(rig.opener.calls.isEmpty())
            assertEquals(0, rig.quits)
        }

    @Test
    fun `restart hands the verified installer to the system and quits`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            val ready = assertIs<UpdateState.Ready>(rig.state)

            assertEquals(RestartResult.Started, rig.updater.restartToUpdate())

            assertEquals(listOf("open ${ready.file}"), rig.opener.calls)
            assertEquals(1, rig.quits)
            // The installer is still there for the system to run.
            assertTrue(ready.file in rig.files.stored)
        }

    @Test
    fun `restart asks first when downloads are active`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            rig.active = 3

            assertEquals(RestartResult.NeedsConfirmation(3), rig.updater.restartToUpdate())
            assertTrue(rig.opener.calls.isEmpty())
            assertEquals(0, rig.quits)
            assertIs<UpdateState.Ready>(rig.state)

            assertEquals(RestartResult.Started, rig.updater.restartToUpdate(confirmed = true))
            assertEquals(1, rig.opener.calls.size)
            assertEquals(1, rig.quits)
        }

    @Test
    fun `an installer the system cannot open is an error and the app keeps running`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            rig.opener.problem = "no app for .dmg"

            val result = rig.updater.restartToUpdate()

            assertEquals(RestartResult.Failed("no app for .dmg"), result)
            assertEquals(0, rig.quits)
            val error = assertIs<UpdateState.Error>(rig.state)
            assertEquals(Version.parse("1.2.0"), error.version)
            assertTrue(error.reason.contains("no app for .dmg"), error.reason)
            assertTrue(rig.files.files.isEmpty())
        }

    @Test
    fun `an installer changed after it was verified is not launched`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            val ready = assertIs<UpdateState.Ready>(rig.state)
            rig.files.stored[ready.file] = "tampered".toByteArray()

            val result = rig.updater.restartToUpdate()

            assertIs<RestartResult.Failed>(result)
            assertTrue(rig.opener.calls.isEmpty())
            assertEquals(0, rig.quits)
            assertIs<UpdateState.Error>(rig.state)
            assertTrue(rig.files.files.isEmpty())
        }

    @Test
    fun `an installer that vanished is not launched`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            val ready = assertIs<UpdateState.Ready>(rig.state)
            rig.files.stored.remove(ready.file)

            assertIs<RestartResult.Failed>(rig.updater.restartToUpdate())
            assertTrue(rig.opener.calls.isEmpty())
            assertEquals(0, rig.quits)
        }

    @Test
    fun `an error state has no installer to launch`() =
        runRig { rig ->
            rig.releases.result = ReleaseCheck.CannotInstall(Version.parse("1.2.0")!!, "v1.2.0", "https://github.com/x", "no sums")
            rig.updater.start()
            runCurrent()

            assertEquals(RestartResult.NotReady, rig.updater.restartToUpdate())
            assertTrue(rig.opener.calls.isEmpty())
        }

    // --- A check that answers while something else moved the state on (S2) ---

    @Test
    fun `a download asked for during a check is ignored and the check's answer wins`() =
        runRig(autoUpdate = false) { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            assertIs<UpdateState.Available>(rig.state)

            val gate = CompletableDeferred<Unit>()
            rig.releases.gate = gate
            val newer = releaseOf("1.3.0")
            rig.found(newer)
            advanceTimeBy(4.hours)
            runCurrent()
            assertEquals(2, rig.releases.calls)

            rig.updater.download()
            runCurrent()
            assertTrue(rig.http.requests.isEmpty())
            assertTrue(rig.files.directories.isEmpty())

            gate.complete(Unit)
            runCurrent()

            assertEquals(UpdateState.Available(newer.version, newer.pageUrl), rig.state)
            assertTrue(rig.http.requests.isEmpty())
        }

    @Test
    fun `turning automatic updates on during a check starts one download of what the check found`() =
        runRig(autoUpdate = false) { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()

            val gate = CompletableDeferred<Unit>()
            rig.releases.gate = gate
            val newer = releaseOf("1.3.0")
            rig.found(newer)
            advanceTimeBy(4.hours)
            runCurrent()

            rig.updater.setAutoUpdate(true)
            runCurrent()
            assertTrue(rig.http.requests.isEmpty())

            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf(newer.asset.url), rig.http.requests.map { it.url })
            assertEquals(newer.version, assertIs<UpdateState.Ready>(rig.state).version)
            assertEquals(1, rig.files.directories.size)
        }

    @Test
    fun `a check that answers during a restart leaves the installer being opened alone`() =
        runRig { rig ->
            val old = releaseOf("1.2.0")
            rig.found(old)
            rig.updater.start()
            runCurrent()
            val ready = assertIs<UpdateState.Ready>(rig.state)

            val checkGate = CompletableDeferred<Unit>()
            rig.releases.gate = checkGate
            val newer = releaseOf("1.3.0")
            rig.found(newer)
            advanceTimeBy(4.hours)
            runCurrent()
            val shaGate = CompletableDeferred<Unit>()
            rig.files.shaGate = shaGate
            val restart = async { rig.updater.restartToUpdate() }
            runCurrent()

            checkGate.complete(Unit)
            runCurrent()
            assertEquals(ready, rig.state)
            assertTrue(rig.files.deleted.isEmpty())

            shaGate.complete(Unit)
            runCurrent()

            assertEquals(RestartResult.Started, restart.await())
            assertEquals(listOf("open ${ready.file}"), rig.opener.calls)
            assertEquals(1, rig.quits)
        }

    @Test
    fun `a check asked for while the installer is being opened is ignored`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            val shaGate = CompletableDeferred<Unit>()
            rig.files.shaGate = shaGate
            val first = async { rig.updater.restartToUpdate() }
            runCurrent()

            rig.updater.checkForUpdates()
            runCurrent()
            assertEquals(1, rig.releases.calls)
            assertTrue(rig.events.isEmpty())

            shaGate.complete(Unit)
            runCurrent()
            assertEquals(RestartResult.Started, first.await())
        }

    @Test
    fun `a second restart while the first one is opening the installer does nothing`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            val shaGate = CompletableDeferred<Unit>()
            rig.files.shaGate = shaGate
            val first = async { rig.updater.restartToUpdate() }
            runCurrent()

            assertEquals(RestartResult.NotReady, rig.updater.restartToUpdate())
            shaGate.complete(Unit)
            runCurrent()

            assertEquals(RestartResult.Started, first.await())
            assertEquals(1, rig.opener.calls.size)
            assertEquals(1, rig.quits)
        }

    @Test
    fun `a check that fails after a failed restart does not bring the deleted installer back`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            val ready = assertIs<UpdateState.Ready>(rig.state)
            val gate = CompletableDeferred<Unit>()
            rig.releases.gate = gate
            rig.releases.result = ReleaseCheck.Failed
            advanceTimeBy(4.hours)
            runCurrent()
            rig.files.stored[ready.file] = "tampered".toByteArray()
            assertIs<RestartResult.Failed>(rig.updater.restartToUpdate())

            gate.complete(Unit)
            runCurrent()

            assertIs<UpdateState.Error>(rig.state)
            assertEquals(RestartResult.NotReady, rig.updater.restartToUpdate())
        }

    @Test
    fun `a check that finds the same release after a failed restart leaves it failed`() =
        runRig { rig ->
            val release = releaseOf("1.2.0")
            rig.found(release)
            rig.updater.start()
            runCurrent()
            val ready = assertIs<UpdateState.Ready>(rig.state)
            val gate = CompletableDeferred<Unit>()
            rig.releases.gate = gate
            advanceTimeBy(4.hours)
            runCurrent()
            rig.files.stored[ready.file] = "tampered".toByteArray()
            assertIs<RestartResult.Failed>(rig.updater.restartToUpdate())

            gate.complete(Unit)
            runCurrent()

            assertIs<UpdateState.Error>(rig.state)
            assertEquals(1, rig.http.requests.size)
        }

    @Test
    fun `once the installer is opened nothing else touches it or opens it again`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            val ready = assertIs<UpdateState.Ready>(rig.state)
            assertEquals(RestartResult.Started, rig.updater.restartToUpdate())

            assertEquals(RestartResult.NotReady, rig.updater.restartToUpdate())
            rig.found(releaseOf("1.3.0"))
            advanceTimeBy(4.hours)
            runCurrent()
            rig.updater.checkForUpdates()
            runCurrent()

            assertEquals(1, rig.opener.calls.size)
            assertEquals(1, rig.releases.calls)
            assertEquals(ready, rig.state)
            assertTrue(ready.file in rig.files.stored)
            assertTrue(rig.files.deleted.isEmpty())
        }

    @Test
    fun `automatic updates turned on during a check download the offer that same check finds again`() =
        runRig(autoUpdate = false) { rig ->
            val release = releaseOf("1.2.0")
            rig.found(release)
            rig.updater.start()
            runCurrent()
            assertIs<UpdateState.Available>(rig.state)

            val gate = CompletableDeferred<Unit>()
            rig.releases.gate = gate
            advanceTimeBy(4.hours)
            runCurrent()
            rig.updater.setAutoUpdate(true)
            runCurrent()
            assertTrue(rig.http.requests.isEmpty())

            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf(release.asset.url), rig.http.requests.map { it.url })
            assertEquals(release.version, assertIs<UpdateState.Ready>(rig.state).version)
        }

    @Test
    fun `the same release found again with automatic updates off stays on offer`() =
        runRig(autoUpdate = false) { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            val offered = rig.state

            advanceTimeBy(4.hours)
            runCurrent()

            assertEquals(offered, rig.state)
            assertTrue(rig.http.requests.isEmpty())
        }

    @Test
    fun `a quit that throws leaves the updater launching so nothing touches the opened installer`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            val ready = assertIs<UpdateState.Ready>(rig.state)
            rig.quitFailure = IllegalStateException("quit failed")

            assertFailsWith<IllegalStateException> { rig.updater.restartToUpdate() }

            assertEquals(RestartResult.NotReady, rig.updater.restartToUpdate())
            rig.updater.checkForUpdates()
            runCurrent()
            assertEquals(1, rig.releases.calls)
            assertEquals(1, rig.opener.calls.size)
            assertEquals(ready, rig.state)
            assertTrue(rig.files.deleted.isEmpty())
        }

    @Test
    fun `after the installer failed to open download tries again`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            rig.opener.problem = "no app for .dmg"
            assertIs<RestartResult.Failed>(rig.updater.restartToUpdate())
            assertIs<UpdateState.Error>(rig.state)

            rig.opener.problem = null
            rig.updater.download()
            runCurrent()

            assertIs<UpdateState.Ready>(rig.state)
            assertEquals(2, rig.files.directories.size)
            assertEquals(RestartResult.Started, rig.updater.restartToUpdate())
        }

    @Test
    fun `a newer release is on offer before the old installer is gone`() =
        runRig(autoUpdate = false) { rig ->
            rig.found(releaseOf("1.1.0"))
            rig.updater.start()
            runCurrent()
            rig.updater.download()
            runCurrent()
            assertIs<UpdateState.Ready>(rig.state)

            val deleteGate = CompletableDeferred<Unit>()
            rig.files.deleteGate = deleteGate
            rig.found(releaseOf("1.2.0"))
            advanceTimeBy(4.hours)
            runCurrent()

            assertEquals(Version.parse("1.2.0"), assertIs<UpdateState.Available>(rig.state).version)
            assertEquals(RestartResult.NotReady, rig.updater.restartToUpdate())
            deleteGate.complete(Unit)
            runCurrent()
            assertEquals(1, rig.files.deleted.size)
        }

    @Test
    fun `a nothing newer answer is shown before the old installer is gone`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            assertIs<UpdateState.Ready>(rig.state)

            val deleteGate = CompletableDeferred<Unit>()
            rig.files.deleteGate = deleteGate
            rig.releases.result = ReleaseCheck.None
            advanceTimeBy(4.hours)
            runCurrent()

            assertEquals(UpdateState.Idle, rig.state)
            deleteGate.complete(Unit)
            runCurrent()
            assertEquals(1, rig.files.deleted.size)
        }

    @Test
    fun `an installer extension without a dot is refused`() =
        runRig { rig ->
            assertFailsWith<IllegalArgumentException> {
                Updater(
                    scope = backgroundScope,
                    current = Version.parse("1.0.0")!!,
                    releases = rig.releases,
                    transport = rig.http,
                    files = rig.files,
                    opener = rig.opener,
                    prefs = rig.prefs,
                    activeDownloads = { 0 },
                    quit = {},
                    installerExtension = "dmg",
                )
            }
        }

    // --- A failed install is not tried again by itself (S3) ---

    @Test
    fun `a failed restart is not downloaded again on the next tick`() =
        runRig { rig ->
            val release = releaseOf("1.2.0")
            rig.found(release)
            rig.updater.start()
            runCurrent()
            rig.opener.problem = "no app for .dmg"
            assertIs<RestartResult.Failed>(rig.updater.restartToUpdate())
            assertEquals(1, rig.http.requests.size)

            advanceTimeBy(4.hours)
            runCurrent()

            assertEquals(2, rig.releases.calls)
            assertEquals(1, rig.http.requests.size)
            assertEquals(1, rig.files.directories.size)
            assertEquals(release.version, assertIs<UpdateState.Error>(rig.state).version)
        }

    @Test
    fun `a failed download is not fetched again on the next tick`() =
        runRig { rig ->
            val release = releaseOf("1.2.0")
            rig.releases.result = ReleaseCheck.Found(release)
            rig.serve(release, failAfter = 1000)
            rig.updater.start()
            runCurrent()
            assertIs<UpdateState.Error>(rig.state)
            assertEquals(1, rig.http.requests.size)

            advanceTimeBy(4.hours)
            runCurrent()

            assertEquals(2, rig.releases.calls)
            assertEquals(1, rig.http.requests.size)
            assertIs<UpdateState.Error>(rig.state)
        }

    @Test
    fun `a failed restart can be tried again by hand after the next check`() =
        runRig(autoUpdate = false) { rig ->
            val release = releaseOf("1.2.0")
            rig.found(release)
            rig.updater.start()
            runCurrent()
            rig.updater.download()
            runCurrent()
            rig.opener.problem = "no app"
            assertIs<RestartResult.Failed>(rig.updater.restartToUpdate())
            advanceTimeBy(4.hours)
            runCurrent()
            assertIs<UpdateState.Error>(rig.state)

            rig.updater.download()
            runCurrent()

            assertIs<UpdateState.Ready>(rig.state)
        }

    @Test
    fun `a newer release after a failed one is offered again`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            rig.opener.problem = "no app"
            assertIs<RestartResult.Failed>(rig.updater.restartToUpdate())

            rig.found(releaseOf("1.3.0"))
            advanceTimeBy(4.hours)
            runCurrent()

            assertEquals(Version.parse("1.3.0"), assertIs<UpdateState.Ready>(rig.state).version)
        }

    // --- A withdrawn release is not offered any more (S4) ---

    @Test
    fun `an available update that the next check no longer lists is withdrawn`() =
        runRig(autoUpdate = false) { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            assertIs<UpdateState.Available>(rig.state)

            rig.releases.result = ReleaseCheck.None
            advanceTimeBy(4.hours)
            runCurrent()

            assertEquals(UpdateState.Idle, rig.state)
            rig.updater.download()
            runCurrent()
            assertTrue(rig.http.requests.isEmpty())
        }

    @Test
    fun `a ready installer that the next check no longer lists is deleted`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            val ready = assertIs<UpdateState.Ready>(rig.state)

            rig.releases.result = ReleaseCheck.None
            advanceTimeBy(4.hours)
            runCurrent()

            assertEquals(UpdateState.Idle, rig.state)
            assertFalse(ready.file in rig.files.stored)
            assertEquals(RestartResult.NotReady, rig.updater.restartToUpdate())
            assertTrue(rig.opener.calls.isEmpty())
        }

    @Test
    fun `an offered update that a check finds not newer than the running version is withdrawn`() =
        runRig(autoUpdate = false) { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()

            rig.found(releaseOf("1.0.0"))
            advanceTimeBy(4.hours)
            runCurrent()

            assertEquals(UpdateState.Idle, rig.state)
        }

    @Test
    fun `an offered update that a check finds uninstallable is replaced by the error`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            val ready = assertIs<UpdateState.Ready>(rig.state)

            rig.releases.result = ReleaseCheck.CannotInstall(Version.parse("1.2.0")!!, "v1.2.0", "https://github.com/x", "no sums")
            advanceTimeBy(4.hours)
            runCurrent()

            assertIs<UpdateState.Error>(rig.state)
            assertFalse(ready.file in rig.files.stored)
        }

    @Test
    fun `a failed check keeps an update on offer`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()
            val ready = rig.state
            assertIs<UpdateState.Ready>(ready)

            rig.releases.result = ReleaseCheck.Failed
            advanceTimeBy(4.hours)
            runCurrent()

            assertEquals(ready, rig.state)
            assertTrue(rig.files.deleted.isEmpty())
        }

    // --- Whatever goes wrong while downloading ends in an error, never stuck (S7) ---

    @Test
    fun `a folder that cannot be made is an error and the schedule goes on`() =
        runRig { rig ->
            rig.files.createFailure = IllegalStateException("no room")
            rig.found(releaseOf("1.2.0"))
            rig.updater.start()
            runCurrent()

            assertIs<UpdateState.Error>(rig.state)
            assertTrue(rig.http.bodies.all { it.closed })

            advanceTimeBy(4.hours)
            runCurrent()
            assertEquals(2, rig.releases.calls)
        }

    @Test
    fun `an unexpected failure while writing is an error and removes the folder`() =
        runRig { rig ->
            rig.files.writeFailure = IllegalStateException("odd")
            val release = releaseOf("1.2.0")
            rig.found(release)
            rig.updater.start()
            runCurrent()

            val error = assertIs<UpdateState.Error>(rig.state)
            assertEquals(release.version, error.version)
            assertEquals(listOf(Path.of("/fake/update-1")), rig.files.deleted)
            assertTrue(rig.files.openSinks.isEmpty())
            assertTrue(rig.http.bodies.all { it.closed })

            advanceTimeBy(4.hours)
            runCurrent()
            assertEquals(2, rig.releases.calls)
            assertEquals(1, rig.http.requests.size)
        }

    // --- Old downloads are swept once, at the start (S5) ---

    @Test
    fun `old download folders are swept once when the updater starts`() =
        runRig { rig ->
            assertEquals(0, rig.files.sweeps)

            rig.updater.start()
            rig.updater.start()
            runCurrent()
            advanceTimeBy(8.hours)
            runCurrent()

            assertEquals(1, rig.files.sweeps)
        }

    // --- An installer of the wrong type is never opened ---

    @Test
    fun `an installer that is not the type of this system is not opened`() =
        runRig { rig ->
            val release = releaseOf("1.2.0", name = "anydm-1.2.0.exe")
            rig.found(release)
            rig.updater.start()
            runCurrent()
            assertIs<UpdateState.Ready>(rig.state)

            val result = rig.updater.restartToUpdate()

            assertIs<RestartResult.Failed>(result)
            assertTrue(rig.opener.calls.isEmpty())
            assertEquals(0, rig.quits)
            assertIs<UpdateState.Error>(rig.state)
            assertTrue(rig.files.files.isEmpty())
        }

    @Test
    fun `the installer type is compared without case`() =
        runRig { rig ->
            rig.found(releaseOf("1.2.0", name = "anydm-1.2.0.DMG"))
            rig.updater.start()
            runCurrent()

            assertEquals(RestartResult.Started, rig.updater.restartToUpdate())
        }

    // --- The real files (S8) ---

    @Test
    fun `an update goes through the real files from download to opening and sweeping`() =
        runBlocking {
            val root = Files.createTempDirectory("anydm-updater-test")
            val job = Job()
            try {
                val files = DiskUpdateFiles(root)
                val release = releaseOf("1.2.0")
                val http = FakeHttp()
                http.routes[release.asset.url] =
                    Answer(
                        body = { FakeBody(installer) },
                        headers = listOf("Content-Length" to installer.size.toString()),
                    )
                val opener = FakeFileActions()
                val updater =
                    Updater(
                        scope = CoroutineScope(coroutineContext + job),
                        current = Version.parse("1.0.0")!!,
                        releases = { ReleaseCheck.Found(release) },
                        transport = http,
                        files = files,
                        opener = opener,
                        prefs = MemoryViewPrefs(autoUpdate = true),
                        activeDownloads = { 0 },
                        quit = {},
                        installerExtension = ".dmg",
                    )

                updater.start()
                val ready = withTimeout(30_000) { updater.state.filterIsInstance<UpdateState.Ready>().first() }

                assertEquals(root, ready.file.parent.parent)
                assertTrue(installer.contentEquals(Files.readAllBytes(ready.file)))
                assertEquals(RestartResult.Started, updater.restartToUpdate())
                assertEquals(listOf("open ${ready.file}"), opener.calls)
                assertTrue(Files.exists(ready.file))
            } finally {
                job.cancelAndJoin()
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `a checksum mismatch through the real files leaves nothing on disk`() =
        runBlocking {
            val root = Files.createTempDirectory("anydm-updater-test")
            val job = Job()
            try {
                val release = releaseOf("1.2.0", sha = "0".repeat(64))
                val http = FakeHttp()
                http.routes[release.asset.url] =
                    Answer(
                        body = { FakeBody(installer) },
                        headers = listOf("Content-Length" to installer.size.toString()),
                    )
                val updater =
                    Updater(
                        scope = CoroutineScope(coroutineContext + job),
                        current = Version.parse("1.0.0")!!,
                        releases = { ReleaseCheck.Found(release) },
                        transport = http,
                        files = DiskUpdateFiles(root),
                        opener = FakeFileActions(),
                        prefs = MemoryViewPrefs(autoUpdate = true),
                        activeDownloads = { 0 },
                        quit = {},
                        installerExtension = ".dmg",
                    )

                updater.start()
                withTimeout(30_000) { updater.state.filterIsInstance<UpdateState.Error>().first() }

                assertEquals(0L, Files.list(root).use { it.count() })
            } finally {
                job.cancelAndJoin()
                root.toFile().deleteRecursively()
            }
        }
}
