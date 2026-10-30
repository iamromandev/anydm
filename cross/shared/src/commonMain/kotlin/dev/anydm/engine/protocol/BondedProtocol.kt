package dev.anydm.engine.protocol

import dev.anydm.engine.core.Download
import dev.anydm.engine.net.Bonding
import dev.anydm.engine.net.ProxyRoute
import dev.anydm.engine.net.ProxyUse
import dev.anydm.engine.net.currentProxyRoute
import dev.anydm.engine.net.withBond
import dev.anydm.engine.net.withNetwork

/** How a protocol's downloads use the networks (BND-03). */
internal enum class BondMode {
    /** Not at all: the default route (torrents bond per session, and media extraction is tied to one address). */
    NONE,

    /** Its workers take a network each, and the blocks are pulled by whoever is free (HTTP). */
    WORKERS,

    /** A whole download is bound to one network, chosen when it starts (FTP, SFTP: one stream can't be split). */
    WHOLE,
}

/**
 * Runs the transfer of [delegate] bound to the networks (BND-02, BND-03), inside the proxy scope [ProxiedProtocol] puts
 * around it: a download that goes through a proxy is never bonded (BND-05). The engine wraps every protocol in one; a
 * protocol knows nothing of it. Probing, planning and finishing stay on the default route.
 */
internal class BondedProtocol(
    private val delegate: Protocol,
    private val bonding: Bonding,
    private val mode: BondMode,
) : Protocol {
    override val ownsFiles: Boolean get() = delegate.ownsFiles

    override suspend fun probe(request: ProbeRequest): StepResult<ProbeResult> = delegate.probe(request)

    override fun plan(
        probe: ProbeResult,
        context: PlanContext,
    ): StepResult<TransferPlan> = delegate.plan(probe, context)

    override suspend fun transfer(
        transfer: Transfer,
        io: TransferIo,
    ): StepResult<Unit> =
        when (mode) {
            BondMode.NONE -> {
                delegate.transfer(transfer, io)
            }

            BondMode.WORKERS -> {
                if (bonding.active().size >=
                    2
                ) {
                    withBond(bonding) { delegate.transfer(transfer, io) }
                } else {
                    delegate.transfer(transfer, io)
                }
            }

            BondMode.WHOLE -> {
                whole(transfer, io)
            }
        }

    // One place on one network for the whole transfer, given back when it ends. Without bonding, or through a proxy, none.
    private suspend fun whole(
        transfer: Transfer,
        io: TransferIo,
    ): StepResult<Unit> {
        val url = transfer.mirror.url
        val use = if (url.startsWith("sftp:", ignoreCase = true)) ProxyUse.SFTP else ProxyUse.FTP
        val lease = if (currentProxyRoute(url, use) == ProxyRoute.Direct) bonding.leaseWhole() else null
        if (lease == null) return delegate.transfer(transfer, io)
        return try {
            withBond(bonding) { withNetwork(lease.network) { delegate.transfer(transfer, io) } }
        } finally {
            lease.close()
        }
    }

    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> = delegate.finalize(transfer)

    override suspend fun relink(request: RelinkRequest): StepResult<Relinked> = delegate.relink(request)

    override fun workFolders(download: Download): List<String> = delegate.workFolders(download)

    override fun setGlobalLimits(
        download: Long?,
        upload: Long?,
    ) = delegate.setGlobalLimits(download, upload)

    override suspend fun resumeSeeding(download: Download): StepResult<Unit> = delegate.resumeSeeding(download)

    override suspend fun close() = delegate.close()
}
