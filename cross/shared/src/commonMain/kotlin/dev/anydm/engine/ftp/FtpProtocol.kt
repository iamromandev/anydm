package dev.anydm.engine.ftp

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.http.CredentialStore
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.PlannedFile
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferIo
import dev.anydm.engine.protocol.TransferPlan
import dev.anydm.engine.store.NewSegment
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What a probe learned about a remote file (FTP-01, FTP-04); [validator] is its size and modification time. */
data class FtpProbe(
    override val name: String,
    override val size: Long?,
    override val validator: String?,
    val target: RemoteTarget,
) : ProbeResult

/**
 * FTP, FTPS and SFTP downloads (FTP-01 to FTP-05) for `DownloadKind.FTP`: the file is split into blocks, each fetched
 * over a connection of its own from [clients], and a stopped download resumes from its blocks' watermarks. The login
 * is the URL's, else the saved one ([credentials], [keys]), else anonymous for FTP.
 */
class FtpProtocol(
    private val clients: RemoteClientFactory,
    private val credentials: CredentialStore,
    private val keys: KeyLogins,
    private val connections: () -> Int = { 4 },
    private val minBlock: Long = MIN_BLOCK,
) : Protocol {
    override suspend fun probe(request: ProbeRequest): StepResult<ProbeResult> {
        val url = FtpUrl.parse(request.mirror.url) ?: return StepResult.Failed(null, "not an FTP, FTPS or SFTP address")
        val login = loginFor(url) ?: return StepResult.Failed(ErrorClass.AUTH, "no login saved for ${url.origin}")
        val target = RemoteTarget(url.scheme, url.host, url.port, url.path)
        val client = clients.create()
        return try {
            client.connect(target, login)
            val stat = client.stat(url.path)
            StepResult.Ok(FtpProbe(url.fileName, stat.size, validatorOf(stat), target))
        } catch (e: RemoteException) {
            e.asFailed()
        } finally {
            withContext(NonCancellable) { client.close() }
        }
    }

    override fun plan(
        probe: ProbeResult,
        context: PlanContext,
    ): StepResult<TransferPlan> {
        check(probe is FtpProbe) { "FtpProtocol can't plan a ${probe::class.simpleName}" }
        val size = probe.size
        val stored = context.stored.firstOrNull { it.file.index == 0 }
        val segments =
            when {
                size == null || size == 0L -> {
                    emptyList()
                }

                else -> {
                    stored
                        ?.takeIf { it.file.size == size && it.segments.isNotEmpty() }
                        ?.segments
                        ?.map { NewSegment(it.start, it.end) }
                        ?: blocksOf(size)
                }
            }
        val path = context.download.fileName ?: probe.name
        return StepResult.Ok(TransferPlan(listOf(PlannedFile(index = 0, path = path, size = size, segments = segments))))
    }

    override suspend fun transfer(
        transfer: Transfer,
        io: TransferIo,
    ): StepResult<Unit> {
        val probe = transfer.probe
        check(probe is FtpProbe) { "FtpProtocol can't transfer a ${probe::class.simpleName}" }
        val url = FtpUrl.parse(transfer.mirror.url) ?: return StepResult.Failed(null, "not an FTP, FTPS or SFTP address")
        val login = loginFor(url) ?: return StepResult.Failed(ErrorClass.AUTH, "no login saved for ${url.origin}")
        return FtpTransfer(clients, probe.target, login, transfer.files.single(), io, connections().coerceIn(1, MAX_BLOCKS)).run()
    }

    /** The transfer already checked that every block arrived; the runner renames the partial into place. */
    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> =
        StepResult.Ok(FinalizeResult(transfer.files.map { it.file.path }))

    // [0, size) in as many equal blocks as connections allow, none under minBlock; the last takes the rest.
    private fun blocksOf(size: Long): List<NewSegment> {
        val count = minOf(connections().coerceIn(1, MAX_BLOCKS).toLong(), maxOf(1L, size / minBlock))
        val block = (size + count - 1) / count
        return (0 until size step block).map { start -> NewSegment(start, minOf(start + block, size)) }
    }

    // The URL's login, else the saved one; for SFTP a key before a password, for FTP anonymous when nothing is saved.
    private suspend fun loginFor(url: FtpUrl): RemoteLogin? {
        url.login?.let { return RemoteLogin.Password(it) }
        if (url.scheme == FtpScheme.SFTP) {
            keys.keyFor(url.origin)?.let { return RemoteLogin.Key(it) }
            return credentials.credentialsFor(url.origin)?.let { RemoteLogin.Password(it) }
        }
        return credentials.credentialsFor(url.origin)?.let { RemoteLogin.Password(it) } ?: RemoteLogin.Anonymous
    }

    private fun validatorOf(stat: RemoteStat): String? =
        if (stat.size == null && stat.modified == null) null else "ftp:${stat.size ?: ""}-${stat.modified ?: ""}"

    companion object {
        /** No block is smaller than this, so a small file isn't split across connections. */
        const val MIN_BLOCK = 1L shl 20

        private const val MAX_BLOCKS = 16
    }
}

/** This failure as a step failure: the class the queue acts on, and the client's message (which holds no secret). */
internal fun RemoteException.asFailed(): StepResult.Failed {
    val errorClass =
        when (failure) {
            RemoteFailure.AUTH, RemoteFailure.CERTIFICATE, RemoteFailure.HOST_KEY_CHANGED -> ErrorClass.AUTH

            RemoteFailure.GONE -> ErrorClass.GONE

            RemoteFailure.PROXY -> ErrorClass.PROXY

            RemoteFailure.TRANSIENT,
            RemoteFailure.TOO_MANY_CONNECTIONS,
            RemoteFailure.REST_UNSUPPORTED,
            RemoteFailure.BIND,
            -> ErrorClass.TRANSIENT
        }
    return StepResult.Failed(errorClass, message?.takeIf { it.isNotBlank() } ?: failure.name.lowercase())
}
