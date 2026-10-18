package dev.anydm.engine.store

import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.AttemptId
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.ChecksumAlgorithm
import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.Container
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.HdrChoice
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.QualityPreset
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.core.TransitionResult
import dev.anydm.engine.store.db.AnydmDb
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Instant

/** What every [DownloadStore] must do, each test on a new database from [newDriver]. Driver tests extend this. */
abstract class DownloadStoreContract : StoreTestBase() {
    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val t1 = Instant.fromEpochMilliseconds(1_700_000_000_123)

    @Test
    fun addAssignsAnIdAndStoresAPendingDownload() =
        runTest {
            val store = newStore().downloads
            val parent = store.add(NewDownload(DownloadKind.SITE, collection = CollectionKind.PLAYLIST))
            val added =
                store.add(
                    NewDownload(
                        kind = DownloadKind.DIRECT,
                        mirrors = listOf("https://example.com/a.mp4", "https://mirror.example.org/a.mp4"),
                        parentId = parent.id,
                        collection = CollectionKind.BATCH,
                        categoryId = CategoryId(4),
                        profileId = ProfileId(6),
                        saveFolder = "/media",
                        fileName = "a.mp4",
                        size = 1_000,
                        priority = 3,
                        speedLimit = 4_096,
                    ),
                )

            added shouldBe
                Download(
                    id = added.id,
                    kind = DownloadKind.DIRECT,
                    status = DownloadStatus.PENDING,
                    parentId = parent.id,
                    collection = CollectionKind.BATCH,
                    categoryId = CategoryId(4),
                    profileId = ProfileId(6),
                    saveFolder = "/media",
                    fileName = "a.mp4",
                    size = 1_000,
                    priority = 3,
                    speedLimit = 4_096,
                    attemptCount = 0,
                    nextAttemptAt = null,
                )
            store.get(added.id) shouldBe added
            store.mirrors(added.id).map { it.url to it.position } shouldBe
                listOf("https://example.com/a.mp4" to 0, "https://mirror.example.org/a.mp4" to 1)
        }

    @Test
    fun everyFieldRoundTrips() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT, fileName = "a.mp4"))

            val queued = (added.transitionTo(DownloadStatus.QUEUED) as TransitionResult.Moved).download
            store.update(queued) shouldBe true
            store.get(added.id) shouldBe queued

            val changed =
                queued.copy(
                    status = DownloadStatus.MUXING,
                    priority = -7,
                    speedLimit = 1,
                    attemptCount = 2,
                    nextAttemptAt = Instant.fromEpochMilliseconds(1_700_000_000_123),
                    saveFolder = "/Видео",
                    fileName = "a 🎬.mp4",
                )
            store.update(changed) shouldBe true
            store.get(added.id) shouldBe changed
        }

    @Test
    fun nullableFieldsStayNull() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT))

            added shouldBe Download(id = added.id, kind = DownloadKind.DIRECT)
            store.get(added.id) shouldBe Download(id = added.id, kind = DownloadKind.DIRECT)
            store.mirrors(added.id) shouldBe emptyList()
        }

    @Test
    fun idsAreNeverReused() =
        runTest {
            val store = newStore().downloads
            val a = store.add(NewDownload(DownloadKind.DIRECT))
            val b = store.add(NewDownload(DownloadKind.DIRECT))
            store.remove(b.id) shouldBe true
            val c = store.add(NewDownload(DownloadKind.DIRECT))

            b.id.value shouldBe a.id.value + 1
            c.id.value shouldBe b.id.value + 1
            store.get(b.id) shouldBe null
        }

    @Test
    fun listsInAddOrderAndQueuedInPriorityOrder() =
        runTest {
            val store = newStore().downloads

            suspend fun queued(priority: Int): Download {
                val added = store.add(NewDownload(DownloadKind.DIRECT, priority = priority))
                return added.copy(status = DownloadStatus.QUEUED).also { store.update(it) }
            }
            val a = queued(0)
            val b = queued(5)
            val c = queued(5)
            val d = queued(0)
            val e = store.add(NewDownload(DownloadKind.DIRECT, priority = 9))

            store.list() shouldBe listOf(a, b, c, d, e)
            store.list(DownloadStatus.QUEUED) shouldBe listOf(b, c, a, d)
            store.list(DownloadStatus.PENDING) shouldBe listOf(e)
            store.list(DownloadStatus.PAUSED) shouldBe emptyList()
        }

    @Test
    fun missingRowsAreReportedNotCreated() =
        runTest {
            val store = newStore().downloads

            store.update(Download(DownloadId(999), DownloadKind.DIRECT)) shouldBe false
            store.updateAttempt(Attempt(AttemptId(999), DownloadId(999), startedAt = t0)) shouldBe false
            store.remove(DownloadId(999)) shouldBe false
            store.get(DownloadId(999)) shouldBe null
            driver.count("download") shouldBe 0
            driver.count("attempt") shouldBe 0
        }

    @Test
    fun enumsAreStoredByName() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.SITE, collection = CollectionKind.PLAYLIST))
            store.update(added.copy(status = DownloadStatus.SEEDING))
            val attempt = store.addAttempt(NewAttempt(added.id, t0))
            store.updateAttempt(attempt.copy(errorClass = ErrorClass.RATE_LIMITED))

            driver.texts("SELECT kind FROM download") shouldBe listOf("SITE")
            driver.texts("SELECT status FROM download") shouldBe listOf("SEEDING")
            driver.texts("SELECT collection FROM download") shouldBe listOf("PLAYLIST")
            driver.texts("SELECT error_class FROM attempt") shouldBe listOf("RATE_LIMITED")
        }

    @Test
    fun anUnknownNameFailsLoudly() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT))
            store.addAttempt(NewAttempt(added.id, t0))

            driver.exec("UPDATE download SET status = 'ARCHIVED'")
            val download = shouldThrow<IncompatibleStoreException> { store.get(added.id) }
            download.message shouldContain "download.status"
            download.message shouldContain "ARCHIVED"

            driver.exec("UPDATE download SET status = 'PENDING'")
            driver.exec("UPDATE attempt SET error_class = 'TIMEOUT'")
            val attempt = shouldThrow<IncompatibleStoreException> { store.attempts(added.id) }
            attempt.message shouldContain "attempt.error_class"
            attempt.message shouldContain "TIMEOUT"
        }

    @Test
    fun instantsKeepTheirMilliseconds() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT))

            val ms = Instant.fromEpochMilliseconds(1_700_000_000_123)
            store.update(added.copy(nextAttemptAt = ms))
            store.get(added.id)?.nextAttemptAt shouldBe ms
            driver.longs("SELECT next_attempt_at FROM download") shouldBe listOf(1_700_000_000_123L)

            val beforeEpoch = Instant.fromEpochMilliseconds(-1)
            store.update(added.copy(nextAttemptAt = beforeEpoch))
            store.get(added.id)?.nextAttemptAt shouldBe beforeEpoch

            // Anything finer than a millisecond is dropped.
            store.update(added.copy(nextAttemptAt = Instant.fromEpochSeconds(1_700_000_000, 123_456_789)))
            store.get(added.id)?.nextAttemptAt shouldBe Instant.fromEpochMilliseconds(1_700_000_000_123)
        }

    @Test
    fun mirrorsComeBackInPositionOrder() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://u0", "https://u1", "https://u2")))

            val mirrors = store.mirrors(added.id)
            mirrors.map { it.url to it.position } shouldBe listOf("https://u0" to 0, "https://u1" to 1, "https://u2" to 2)
            mirrors.map { it.downloadId }.toSet() shouldBe setOf(added.id)
            mirrors.map { it.id }.toSet().size shouldBe 3
            mirrors[1] shouldBe Mirror(mirrors[1].id, added.id, "https://u1", 1)
        }

    @Test
    fun updateMirrorSavesTheUrl() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://u0", "https://u1")))
            val first = store.mirrors(added.id).first()

            store.updateMirror(first.copy(url = "https://fresh")) shouldBe true

            store.mirrors(added.id).map { it.url } shouldBe listOf("https://fresh", "https://u1")
        }

    @Test
    fun updateMirrorOfAMissingMirrorIsFalse() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT))

            store.updateMirror(Mirror(MirrorId(999), added.id, "https://u", 0)) shouldBe false
        }

    @Test
    fun aRequestContextRoundTrips() =
        runTest {
            val store = newStore().downloads
            val context =
                RequestContext(listOf("X-A" to "1", "X-B" to "two"), referrer = "https://r/", userAgent = "UA", cookies = "a=1; b=2")
            val added = store.add(NewDownload(DownloadKind.DIRECT, context = context))

            store.context(added.id) shouldBe context
            val other = RequestContext(userAgent = "Other")
            store.setContext(added.id, other) shouldBe true
            store.context(added.id) shouldBe other
            store.setContext(added.id, null) shouldBe true
            store.context(added.id) shouldBe null
            store.setContext(DownloadId(999), other) shouldBe false
        }

    @Test
    fun aRequestContextGoesWithItsDownload() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT, context = RequestContext(userAgent = "UA")))

            store.remove(added.id)

            store.context(added.id) shouldBe null
            driver.count("request_context") shouldBe 0L
        }

    @Test
    fun addFileTakesTheNextIndexAndSelectsIt() =
        runTest {
            val store = newStore().downloads
            val id = store.add(NewDownload(DownloadKind.DIRECT)).id
            store.replaceFiles(id, listOf(NewFile(0, "a.bin", 10), NewFile(3, "b.bin", 20)))

            val added = store.addFile(id, "c.bin", 30)

            added.index shouldBe 4
            added.path shouldBe "c.bin"
            added.size shouldBe 30L
            added.selected shouldBe true
            store.files(id).map { it.path } shouldBe listOf("a.bin", "b.bin", "c.bin")
        }

    @Test
    fun addFileToAMissingDownloadThrows() =
        runTest {
            val store = newStore().downloads

            shouldThrow<IllegalStateException> { store.addFile(DownloadId(999), "x.bin", null) }
        }

    @Test
    fun removeFileDropsTheFileAndItsSegments() =
        runTest {
            val store = newStore().downloads
            val id = store.add(NewDownload(DownloadKind.DIRECT)).id
            val (first, second) = store.replaceFiles(id, listOf(NewFile(0, "a.bin", 10), NewFile(1, "b.bin", 10)))
            store.replaceSegments(first.id, listOf(NewSegment(0, 10)))
            store.replaceSegments(second.id, listOf(NewSegment(0, 10)))

            store.removeFile(first.id)
            store.removeFile(FileId(999))

            store.files(id).map { it.path } shouldBe listOf("b.bin")
            store.segments(first.id) shouldBe emptyList()
            store.segments(second.id).size shouldBe 1
        }

    @Test
    fun aFormatSurvivesTheStore() =
        runTest {
            val store = newStore().downloads
            val format =
                Format(
                    QualityPreset.SMALL,
                    height = 480,
                    fps = 30,
                    hdr = HdrChoice.NO,
                    videoCodec = "avc1",
                    audioCodec = "mp4a",
                    container = Container.MKV,
                )

            val added = store.add(NewDownload(DownloadKind.SITE, format = format))

            store.format(added.id) shouldBe format
        }

    @Test
    fun noFormatReadsAsNull() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.SITE))

            store.format(added.id) shouldBe null
        }

    @Test
    fun removingADownloadDropsItsFormat() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.SITE, format = Format()))

            store.remove(added.id)

            store.format(added.id) shouldBe null
            driver.count("format") shouldBe 0L
        }

    @Test
    fun aChecksumRoundTrips() =
        runTest {
            val store = newStore().downloads
            val checksum = Checksum(ChecksumAlgorithm.SHA256, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
            store.add(NewDownload(DownloadKind.DIRECT)).checksum shouldBe null
            val added = store.add(NewDownload(DownloadKind.DIRECT, checksum = checksum))
            added.checksum shouldBe checksum
            store.get(added.id)!!.checksum shouldBe checksum
            store.list().last().checksum shouldBe checksum

            store.update(added.copy(checksum = null)) shouldBe true

            store.get(added.id)!!.checksum shouldBe null
        }

    @Test
    fun sourceValidatorRoundTrips() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT))
            added.sourceValidator shouldBe null

            store.update(added.copy(sourceValidator = "\"etag-1\"")) shouldBe true

            store.get(added.id)!!.sourceValidator shouldBe "\"etag-1\""
        }

    @Test
    fun pauseReasonsAndQueuedAtRoundTrip() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT), queuedAt = t0)
            added.queuedAt shouldBe t0

            val paused =
                (added.pause(PauseReason.USER) as TransitionResult.Moved)
                    .download
                    .let { (it.pause(PauseReason.GLOBAL) as TransitionResult.Moved).download }
                    .copy(queuedAt = t1)
            store.update(paused) shouldBe true

            store.get(added.id) shouldBe paused
            store.get(added.id)!!.pauseReasons shouldBe setOf(PauseReason.USER, PauseReason.GLOBAL)
        }

    @Test
    fun anUnknownPauseReasonIsIgnoredOnLoad() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT))

            driver.exec("UPDATE download SET status = 'PAUSED', pause_reasons = 'USER,FUTURE' WHERE id = ${added.id.value}")

            store.get(added.id)!!.pauseReasons shouldBe setOf(PauseReason.USER)
        }

    @Test
    fun listByStatusBreaksPriorityTiesByQueuedAt() =
        runTest {
            val store = newStore().downloads
            val t2 = Instant.fromEpochMilliseconds(1_700_000_000_456)
            for (at in listOf(t2, t0, t1)) {
                val added = store.add(NewDownload(DownloadKind.DIRECT, priority = 1), queuedAt = at)
                store.update((added.transitionTo(DownloadStatus.QUEUED) as TransitionResult.Moved).download)
            }

            store.list(DownloadStatus.QUEUED).map { it.queuedAt } shouldBe listOf(t0, t1, t2)
        }

    @Test
    fun filesAndSegmentsRoundTrip() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT))

            val files =
                store.replaceFiles(
                    added.id,
                    listOf(NewFile(1, "b.mp4", size = null, selected = false), NewFile(0, "a.mp4", size = 1_000)),
                )
            files.map { it.index } shouldBe listOf(0, 1)
            files[0] shouldBe File(files[0].id, added.id, 0, "a.mp4", 1_000, true)
            files[1] shouldBe File(files[1].id, added.id, 1, "b.mp4", null, false)
            store.files(added.id) shouldBe files

            val segments = store.replaceSegments(files[0].id, listOf(NewSegment(500, 1_000), NewSegment(0, 500)))
            segments.map { it.start to it.end } shouldBe listOf(0L to 500L, 500L to 1_000L)
            segments[0] shouldBe Segment(segments[0].id, files[0].id, 0, 500, 0)
            segments[1] shouldBe Segment(segments[1].id, files[0].id, 500, 1_000, 500)
            store.segments(files[0].id) shouldBe segments
            store.segments(files[1].id) shouldBe emptyList()
        }

    @Test
    fun replacingFilesDropsTheOldOnesWithTheirSegments() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT))
            val first = store.replaceFiles(added.id, listOf(NewFile(0, "a.mp4", size = 1_000)))
            store.replaceSegments(first.single().id, listOf(NewSegment(0, 1_000)))
            driver.count("segment") shouldBe 1

            val second = store.replaceFiles(added.id, listOf(NewFile(0, "b.mp4")))
            driver.count("segment") shouldBe 0
            driver.count("file") shouldBe 1
            store.files(added.id) shouldBe second
            second.single().path shouldBe "b.mp4"

            shouldThrow<IllegalArgumentException> {
                store.replaceFiles(added.id, listOf(NewFile(0, "x"), NewFile(0, "y")))
            }
            store.files(added.id) shouldBe second
        }

    @Test
    fun watermarksAreSavedAllOrNothing() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT))
            val file = store.replaceFiles(added.id, listOf(NewFile(0, "a.mp4", size = 1_000))).single()
            val (s0, s1) = store.replaceSegments(file.id, listOf(NewSegment(0, 500), NewSegment(500, 1_000)))

            // A segment that has since been removed is skipped.
            store.saveWatermarks(mapOf(s0.id to 250L, s1.id to 1_000L, SegmentId(999) to 5L))
            store.segments(file.id).map { it.watermark } shouldBe listOf(250L, 1_000L)

            val failure = shouldThrow<Exception> { store.saveWatermarks(mapOf(s0.id to 300L, s1.id to 1_001L)) }
            failure.message shouldContain "CHECK constraint failed"
            store.segments(file.id).map { it.watermark } shouldBe listOf(250L, 1_000L)
        }

    @Test
    fun openAttemptsListsOnlyUnendedAttemptsOldestFirst() =
        runTest {
            val store = newStore().downloads
            val one = store.add(NewDownload(DownloadKind.DIRECT))
            val two = store.add(NewDownload(DownloadKind.DIRECT))

            val a1 = store.addAttempt(NewAttempt(one.id, t0))
            val a2 = store.addAttempt(NewAttempt(one.id, t0))
            store.updateAttempt(a2.copy(endedAt = t1))
            val a3 = store.addAttempt(NewAttempt(two.id, t1))

            store.openAttempts().map { it.id } shouldBe listOf(a1.id, a3.id)
        }

    @Test
    fun attemptsAreAppendedAndEnded() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://u0")))
            val m0 = store.mirrors(added.id).single()

            val first = store.addAttempt(NewAttempt(added.id, t0, m0.id))
            first shouldBe Attempt(first.id, added.id, startedAt = t0, mirrorId = m0.id)

            val ended = first.copy(endedAt = t1, errorClass = ErrorClass.RATE_LIMITED, message = "429")
            store.updateAttempt(ended) shouldBe true

            val second = store.addAttempt(NewAttempt(added.id, t1))
            second shouldBe Attempt(second.id, added.id, startedAt = t1)
            store.attempts(added.id) shouldBe listOf(ended, second)
        }

    @Test
    fun removeTakesEverythingWithIt() =
        runTest {
            val store = newStore().downloads
            val collection = store.add(NewDownload(DownloadKind.SITE, collection = CollectionKind.PLAYLIST))
            val child = store.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://u0"), parentId = collection.id))
            val file = store.replaceFiles(child.id, listOf(NewFile(0, "a.mp4", size = 1_000))).single()
            store.replaceSegments(file.id, listOf(NewSegment(0, 1_000)))
            store.addAttempt(NewAttempt(child.id, t0))
            driver.count("download") shouldBe 2
            driver.count("mirror") shouldBe 1
            driver.count("file") shouldBe 1
            driver.count("segment") shouldBe 1
            driver.count("attempt") shouldBe 1

            store.remove(collection.id) shouldBe true

            for (table in listOf("download", "mirror", "file", "segment", "attempt")) {
                withClue(table) { driver.count(table) shouldBe 0 }
            }
        }

    @Test
    fun aRemovedMirrorLeavesItsAttempts() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://u0")))
            val mirror = store.mirrors(added.id).single()
            val attempt = store.addAttempt(NewAttempt(added.id, t0, mirror.id))

            driver.exec("DELETE FROM mirror")

            store.attempts(added.id) shouldBe listOf(attempt.copy(mirrorId = null))
        }

    @Test
    fun childRowsNeedTheirParent() =
        runTest {
            val store = newStore().downloads
            // The replace calls say so themselves, with or without anything to write; the rest are the database's.
            val failures =
                listOf<Pair<String, suspend () -> Unit>>(
                    "download 999 does not exist" to { store.replaceFiles(DownloadId(999), listOf(NewFile(0, "a.mp4"))) },
                    "download 999 does not exist" to { store.replaceFiles(DownloadId(999), emptyList()) },
                    "file 999 does not exist" to { store.replaceSegments(FileId(999), listOf(NewSegment(0, 10))) },
                    "file 999 does not exist" to { store.replaceSegments(FileId(999), emptyList()) },
                    "FOREIGN KEY constraint failed" to { store.addAttempt(NewAttempt(DownloadId(999), t0)) },
                    "FOREIGN KEY constraint failed" to
                        { store.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://u0"), parentId = DownloadId(999))) },
                )
            for ((i, failure) in failures.withIndex()) {
                val (message, call) = failure
                withClue("call $i") { shouldThrow<Exception> { call() }.message shouldContain message }
            }

            for (table in listOf("download", "mirror", "file", "segment", "attempt")) {
                withClue(table) { driver.count(table) shouldBe 0 }
            }
        }

    // A caller cancelled while its write waits for the database (a pause or quit saving watermarks from a finally
    // block, FND-09) still gets the write made: the dispatcher here holds it until after the cancel, the worst case.
    @Test
    fun aCancelledAddStillHappens() =
        runTest {
            val store = newStore().downloads

            val call =
                launch(start = CoroutineStart.UNDISPATCHED) { store.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://u0"))) }
            call.cancel()
            testScheduler.advanceUntilIdle()
            call.join()

            driver.count("download") shouldBe 1
            driver.count("mirror") shouldBe 1
        }

    @Test
    fun aCancelledSaveWatermarksStillHappens() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT))
            val file = store.replaceFiles(added.id, listOf(NewFile(0, "a.mp4", size = 1_000))).single()
            val segment = store.replaceSegments(file.id, listOf(NewSegment(0, 1_000))).single()

            val call = launch(start = CoroutineStart.UNDISPATCHED) { store.saveWatermarks(mapOf(segment.id to 400L)) }
            call.cancel()
            testScheduler.advanceUntilIdle()
            call.join()

            store.segments(file.id).single().watermark shouldBe 400L
        }

    @Test
    fun aCancelledUpdateStillHappens() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT))

            val call = launch(start = CoroutineStart.UNDISPATCHED) { store.update(added.copy(status = DownloadStatus.PAUSED)) }
            call.cancel()
            testScheduler.advanceUntilIdle()
            call.join()

            store.get(added.id)?.status shouldBe DownloadStatus.PAUSED
        }

    @Test
    fun checksRejectImpossibleRows() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://u0"), size = 1_000, speedLimit = 5))
            val file = store.replaceFiles(added.id, listOf(NewFile(0, "a.mp4", size = 1_000))).single()
            store.replaceSegments(file.id, listOf(NewSegment(0, 1_000)))

            suspend fun snapshot() = listOf(store.get(added.id), store.mirrors(added.id), store.files(added.id), store.segments(file.id))
            val before = snapshot()

            val impossible =
                listOf(
                    "UPDATE download SET size = -1",
                    "UPDATE download SET speed_limit = 0",
                    "UPDATE download SET attempt_count = -1",
                    "UPDATE download SET parent_id = id",
                    "UPDATE file SET file_index = -1",
                    "UPDATE file SET size = -1",
                    "UPDATE segment SET start_byte = -1",
                    "UPDATE segment SET end_byte = start_byte",
                    "UPDATE segment SET watermark = end_byte + 1",
                    "UPDATE mirror SET position = -1",
                )
            for (sql in impossible) {
                withClue(sql) { shouldThrow<Exception> { driver.exec(sql) }.message shouldContain "CHECK constraint failed" }
            }

            snapshot() shouldBe before
        }

    @Test
    fun theDatabaseIsCurrentWithForeignKeysOn() =
        runTest {
            newStore()

            driver.userVersion() shouldBe AnydmDb.Schema.version
            driver.longs("PRAGMA foreign_keys") shouldBe listOf(1L)
        }

    @Test
    fun randomDownloadsRoundTrip() =
        runTest {
            val store = newStore().downloads
            val parent = store.add(NewDownload(DownloadKind.SITE, collection = CollectionKind.PLAYLIST))

            checkAll(
                200,
                arbNewDownload,
                Arb.boolean(),
                Arb.element(DownloadStatus.entries),
                Arb.int(0..Int.MAX_VALUE),
                arbInstant.orNull(),
            ) { new, isChild, status, attempts, next ->
                val input = new.copy(parentId = if (isChild) parent.id else null)
                val added = store.add(input)
                added shouldBe
                    Download(
                        id = added.id,
                        kind = new.kind,
                        status = DownloadStatus.PENDING,
                        parentId = if (isChild) parent.id else null,
                        collection = new.collection,
                        categoryId = new.categoryId,
                        profileId = new.profileId,
                        saveFolder = new.saveFolder,
                        fileName = new.fileName,
                        size = new.size,
                        priority = new.priority,
                        speedLimit = new.speedLimit,
                        attemptCount = 0,
                        nextAttemptAt = null,
                    )

                val updated = added.copy(status = status, attemptCount = attempts, nextAttemptAt = next)
                store.update(updated) shouldBe true
                store.get(added.id) shouldBe updated
                store.mirrors(added.id).map { it.url } shouldBe new.mirrors
            }
        }

    @Test
    fun allowDuplicateRoundTrips() =
        runTest {
            val store = newStore().downloads
            store.add(NewDownload(DownloadKind.DIRECT)).allowDuplicate shouldBe false
            val added = store.add(NewDownload(DownloadKind.DIRECT, allowDuplicate = true))
            added.allowDuplicate shouldBe true
            store.get(added.id)!!.allowDuplicate shouldBe true

            store.update(added.copy(allowDuplicate = false)) shouldBe true

            store.get(added.id)!!.allowDuplicate shouldBe false
        }

    @Test
    fun allMirrorsListsTheMirrorsOfEveryDownload() =
        runTest {
            val store = newStore().downloads
            val one = store.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://a/1", "https://b/1")))
            val two = store.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://a/2")))
            store.add(NewDownload(DownloadKind.DIRECT))

            store.allMirrors().map { Triple(it.downloadId, it.url, it.position) } shouldBe
                listOf(Triple(one.id, "https://a/1", 0), Triple(one.id, "https://b/1", 1), Triple(two.id, "https://a/2", 0))
        }

    @Test
    fun aPlacedPathRoundTrips() =
        runTest {
            val store = newStore().downloads
            val added = store.add(NewDownload(DownloadKind.DIRECT))
            val file = store.replaceFiles(added.id, listOf(NewFile(0, "a.mp4", size = 10))).single()
            file.placedPath shouldBe null

            store.setPlacedPath(file.id, "a (1).mp4")

            store.files(added.id).single().placedPath shouldBe "a (1).mp4"
            store.replaceFiles(added.id, listOf(NewFile(0, "a.mp4", size = 10))).single().placedPath shouldBe null
        }
}
