package dev.anydm.engine.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DownloadTest {
    @Test
    fun startsPendingWithNothingKnownYet() {
        val download = Download(DownloadId(7), DownloadKind.SITE)

        download.status shouldBe DownloadStatus.PENDING
        download.parentId shouldBe null
        download.collection shouldBe null
        download.categoryId shouldBe null
        download.profileId shouldBe null
        download.saveFolder shouldBe null
        download.fileName shouldBe null
        download.size shouldBe null
        download.speedLimit shouldBe null
        download.nextAttemptAt shouldBe null
        download.priority shouldBe 0
        download.attemptCount shouldBe 0
    }

    @Test
    fun rejectsImpossibleValues() {
        val download = aDownload()

        shouldThrow<IllegalArgumentException> { download.copy(size = -1) }
        shouldThrow<IllegalArgumentException> { download.copy(speedLimit = 0) }
        shouldThrow<IllegalArgumentException> { download.copy(attemptCount = -1) }
        shouldThrow<IllegalArgumentException> { download.copy(parentId = DownloadId(1)) }
    }

    @Test
    fun aCollectionIsADownloadThatChildrenPointAt() {
        val playlist = Download(DownloadId(1), DownloadKind.SITE, collection = CollectionKind.PLAYLIST)
        val child = Download(DownloadId(2), DownloadKind.SITE, parentId = playlist.id)

        playlist.isCollection shouldBe true
        child.parentId shouldBe playlist.id
        child.isCollection shouldBe false
    }

    @Test
    fun kindsAreTheSpecsFour() {
        DownloadKind.entries.map { it.name } shouldBe listOf("DIRECT", "SITE", "TORRENT", "FTP")
    }
}
