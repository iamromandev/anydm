package dev.anydm.engine.ftp

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.core.aPendingDownload
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.http.Credentials
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.TransferFile
import dev.anydm.engine.protocol.TransferPlan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

class FtpProtocolPlanTest {
    private val server = FakeRemoteServer()
    private val keychain = FakeKeychain()
    private val logins = KeychainCredentials(keychain)
    private val mib = 1L shl 20

    private fun protocol(connections: Int = 4) = FtpProtocol(server.factory, logins, logins, connections = { connections })

    private fun request(url: String) = ProbeRequest(aPendingDownload(DownloadKind.FTP), Mirror(MirrorId(1), DownloadId(1), url, 0))

    private suspend fun FtpProtocol.probed(url: String): FtpProbe =
        probe(request(url)).shouldBeInstanceOf<StepResult.Ok<*>>().value.shouldBeInstanceOf<FtpProbe>()

    private fun FtpProtocol.planned(
        probe: FtpProbe,
        stored: List<TransferFile> = emptyList(),
        fileName: String? = null,
    ) = plan(probe, PlanContext(aPendingDownload(DownloadKind.FTP, fileName = fileName), stored))
        .shouldBeInstanceOf<StepResult.Ok<*>>()
        .value
        .let { (it as TransferPlan).files.single() }

    private fun failure(result: StepResult<*>) = result.shouldBeInstanceOf<StepResult.Failed>()

    @Test
    fun probeReadsNameSizeAndValidator() =
        runTest {
            server.files["/pub/a.bin"] = ByteArray(1000)

            val probe = protocol().probed("ftp://host/pub/a.bin")

            probe.name shouldBe "a.bin"
            probe.size shouldBe 1000L
            probe.validator shouldBe "ftp:1000-1700000000"
            server.closed shouldBe server.opened
        }

    @Test
    fun theFileNameComesFromThePercentDecodedPath() =
        runTest {
            server.files["/my dir/é file.txt"] = ByteArray(10)

            protocol().probed("ftp://host/my%20dir/%C3%A9%20file.txt").name shouldBe "é file.txt"
        }

    @Test
    fun theValidatorChangesWithSizeOrModified() =
        runTest {
            server.files["/a"] = ByteArray(10)
            val first = protocol().probed("ftp://host/a").validator
            server.modified = 1_800_000_000L
            val second = protocol().probed("ftp://host/a").validator
            server.files["/a"] = ByteArray(11)
            val third = protocol().probed("ftp://host/a").validator

            setOf(first, second, third).size shouldBe 3
        }

    @Test
    fun aServerThatReportsNothingGivesNoValidator() =
        runTest {
            server.files["/a"] = ByteArray(10)
            server.reportsSize = false
            server.modified = null

            val probe = protocol().probed("ftp://host/a")

            probe.size shouldBe null
            probe.validator shouldBe null
        }

    @Test
    fun aMissingFileIsGone() =
        runTest {
            failure(protocol().probe(request("ftp://host/none"))).errorClass shouldBe ErrorClass.GONE
        }

    @Test
    fun aRejectedLoginIsAuth() =
        runTest {
            server.files["/a"] = ByteArray(1)
            server.connectFailure = RemoteException(RemoteFailure.AUTH, "login refused")

            failure(protocol().probe(request("ftp://host/a"))).errorClass shouldBe ErrorClass.AUTH
        }

    @Test
    fun aDroppedConnectionIsTransient() =
        runTest {
            server.files["/a"] = ByteArray(1)
            server.statFailure = RemoteException(RemoteFailure.TRANSIENT, "reset")

            failure(protocol().probe(request("ftp://host/a"))).errorClass shouldBe ErrorClass.TRANSIENT
        }

    @Test
    fun aBadCertificateOrAChangedHostKeyIsAuth() =
        runTest {
            server.files["/a"] = ByteArray(1)
            for (kind in listOf(RemoteFailure.CERTIFICATE, RemoteFailure.HOST_KEY_CHANGED)) {
                server.connectFailure = RemoteException(kind, "untrusted")
                failure(protocol().probe(request("ftps://host/a"))).errorClass shouldBe ErrorClass.AUTH
            }
        }

    @Test
    fun anAddressThatIsNotFtpFails() =
        runTest {
            failure(protocol().probe(request("https://host/a"))).errorClass shouldBe null
        }

    @Test
    fun theLoginIsTheUrlsThenTheStoredOneThenAnonymousForFtp() =
        runTest {
            server.files["/a"] = ByteArray(1)

            protocol().probed("ftp://host/a")
            server.connects.last().second shouldBe RemoteLogin.Anonymous

            logins.save("ftp://host:21", Credentials("stored", "pw"))
            protocol().probed("ftp://host/a")
            server.connects.last().second shouldBe RemoteLogin.Password(Credentials("stored", "pw"))

            protocol().probed("ftp://ann:secret@host/a")
            server.connects.last().second shouldBe RemoteLogin.Password(Credentials("ann", "secret"))
        }

    @Test
    fun sftpWithNoLoginIsAuth() =
        runTest {
            server.files["/a"] = ByteArray(1)

            failure(protocol().probe(request("sftp://host/a"))).errorClass shouldBe ErrorClass.AUTH
            server.opened shouldBe 0
        }

    @Test
    fun aKeyLoginIsUsedBeforeAPassword() =
        runTest {
            server.files["/a"] = ByteArray(1)
            logins.save("sftp://host:22", Credentials("ann", "pw"))
            logins.saveKey("sftp://host:22", SshKeyLogin("ann", "/k", "phrase"))

            protocol().probed("sftp://host/a")

            server.connects.single().second shouldBe RemoteLogin.Key(SshKeyLogin("ann", "/k", "phrase"))
        }

    @Test
    fun theTargetCarriesSchemeHostPortAndPath() =
        runTest {
            server.files["/x/y"] = ByteArray(1)
            logins.save("sftp://[::1]:2222", Credentials("ann", "pw"))

            protocol().probed("sftp://[::1]:2222/x/y")

            server.connects.single().first shouldBe RemoteTarget(FtpScheme.SFTP, "[::1]", 2222, "/x/y")
        }

    @Test
    fun aServerWithoutASizeGetsNoSegments() =
        runTest {
            server.files["/a"] = ByteArray(5 * mib.toInt())
            server.reportsSize = false
            val protocol = protocol()

            protocol.planned(protocol.probed("ftp://host/a")).segments shouldBe emptyList()
        }

    @Test
    fun aZeroLengthFileHasOneEmptyPlannedFileAndNoSegments() =
        runTest {
            server.files["/empty"] = ByteArray(0)
            val protocol = protocol()

            val file = protocol.planned(protocol.probed("ftp://host/empty"))

            file.size shouldBe 0L
            file.segments shouldBe emptyList()
        }

    @Test
    fun aBigFileIsSplitIntoConnectionsPerDownloadBlocksOfAtLeastOneMiB() =
        runTest {
            server.files["/big"] = ByteArray(10 * mib.toInt())
            server.files["/mid"] = ByteArray(3 * mib.toInt())
            server.files["/small"] = ByteArray(500)
            val protocol = protocol(connections = 4)

            protocol.planned(protocol.probed("ftp://host/big")).segments.size shouldBe 4
            protocol.planned(protocol.probed("ftp://host/mid")).segments.size shouldBe 3
            protocol.planned(protocol.probed("ftp://host/small")).segments.size shouldBe 1
        }

    @Test
    fun blocksCoverTheFileInOrder() =
        runTest {
            server.files["/big"] = ByteArray(10 * mib.toInt() + 7)
            val protocol = protocol(connections = 3)

            val file = protocol.planned(protocol.probed("ftp://host/big"))

            file.segments.first().start shouldBe 0L
            file.segments.last().end shouldBe file.size
            file.segments.zipWithNext().all { (a, b) -> a.end == b.start } shouldBe true
            file.segments.all { it.end - it.start >= mib } shouldBe true
        }

    @Test
    fun aStoredFilePlansFromItsBlocks() =
        runTest {
            server.files["/big"] = ByteArray(8 * mib.toInt())
            val protocol = protocol(connections = 4)
            val probe = protocol.probed("ftp://host/big")
            val row = File(FileId(1), DownloadId(1), 0, "big", 8 * mib)
            val stored =
                TransferFile(
                    row,
                    listOf(
                        Segment(SegmentId(1), FileId(1), 0, 3 * mib, watermark = mib),
                        Segment(SegmentId(2), FileId(1), 3 * mib, 8 * mib),
                    ),
                    Path(stepRoot, "big.1.anydm"),
                    Path(stepRoot, "big"),
                )

            val file = protocol.planned(probe, listOf(stored))

            file.segments.map { it.start to it.end } shouldBe listOf(0L to 3 * mib, 3 * mib to 8 * mib)
        }

    @Test
    fun theUsersFileNameWinsOverTheProbes() =
        runTest {
            server.files["/a.bin"] = ByteArray(5)
            val protocol = protocol()

            protocol.planned(protocol.probed("ftp://host/a.bin"), fileName = "mine.bin").path shouldBe "mine.bin"
            protocol.planned(protocol.probed("ftp://host/a.bin")).path shouldBe "a.bin"
        }

    @Test
    fun noFailureMessageHoldsAPassword() =
        runTest {
            server.files["/a"] = ByteArray(1)
            for (kind in listOf(RemoteFailure.AUTH, RemoteFailure.GONE, RemoteFailure.TRANSIENT, RemoteFailure.CERTIFICATE)) {
                server.connectFailure = RemoteException(kind, "refused")
                failure(protocol().probe(request("ftp://ann:hunter2@host/a"))).message shouldNotContain "hunter2"
            }
        }
}
