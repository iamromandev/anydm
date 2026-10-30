package dev.anydm.engine.core

import dev.anydm.engine.core.DownloadStatus.CANCELLED
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DownloadTransitionTableTest {
    @Test
    fun expectedTableNamesEveryStatus() {
        expectedTransitions.keys shouldBe DownloadStatus.entries.toSet()
    }

    @Test
    fun everyPairOfStatusesIsAllowedOrRejectedAsSpecified() {
        val mismatches =
            buildList {
                for (from in DownloadStatus.entries) {
                    for (to in DownloadStatus.entries) {
                        val download = aDownload(from)
                        val legal = to in expectedTransitions.getValue(from)
                        val expected =
                            if (legal) {
                                TransitionResult.Moved(download.copy(status = to))
                            } else {
                                TransitionResult.Rejected(from = from, to = to)
                            }
                        val actual = download.transitionTo(to)
                        if (actual != expected || from.canMoveTo(to) != legal) {
                            add("$from -> $to: expected $expected, got $actual")
                        }
                    }
                }
            }
        mismatches.shouldBeEmpty()
    }

    @Test
    fun onlyCompletedAndCancelledAreTerminal() {
        DownloadStatus.entries.filter { it.isTerminal }.toSet() shouldBe setOf(COMPLETED, CANCELLED)
    }
}
