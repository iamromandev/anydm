package dev.anydm.engine.core

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class DownloadTransitionPropertyTest {
    @Test
    fun randomTransitionsLeaveADownloadInALegalState() =
        runTest {
            checkAll(Arb.list(Arb.element(DownloadStatus.entries), 0..40)) { targets ->
                var download = aDownload()
                for (target in targets) {
                    val before = download
                    val legal = target in expectedTransitions.getValue(before.status)
                    when (val result = before.transitionTo(target)) {
                        is TransitionResult.Moved -> {
                            withClue("${before.status} -> $target was allowed") { legal shouldBe true }
                            result.download shouldBe before.copy(status = target)
                            download = result.download
                        }

                        is TransitionResult.Rejected -> {
                            withClue("${before.status} -> $target was rejected") { legal shouldBe false }
                            result shouldBe TransitionResult.Rejected(from = before.status, to = target)
                        }
                    }
                }
                // Only the status ever changed.
                download shouldBe aDownload().copy(status = download.status)
            }
        }

    @Test
    fun randomLegalWalksAreAlwaysAccepted() =
        runTest {
            checkAll(Arb.list(Arb.int(0..<16), 1..60)) { picks ->
                var download = aDownload()
                for (pick in picks) {
                    val options = expectedTransitions.getValue(download.status).sortedBy { it.ordinal }
                    if (options.isEmpty()) {
                        DownloadStatus.entries.forEach {
                            download.transitionTo(it).shouldBeInstanceOf<TransitionResult.Rejected>()
                        }
                        break
                    }
                    val target = options[pick % options.size]
                    download.transitionTo(target) shouldBe TransitionResult.Moved(download.copy(status = target))
                    download = download.copy(status = target)
                }
            }
        }
}
