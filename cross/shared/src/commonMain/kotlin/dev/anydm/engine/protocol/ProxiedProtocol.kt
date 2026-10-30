package dev.anydm.engine.protocol

import dev.anydm.engine.core.Download
import dev.anydm.engine.net.ProxyRouter
import dev.anydm.engine.net.withProxy

/**
 * Runs each step of [delegate] inside its download's proxy scope (NET-15), so every connection the step opens, in any
 * worker it starts, asks [router] for its route. The engine wraps every protocol in one; a protocol knows nothing of it.
 */
internal class ProxiedProtocol(
    private val delegate: Protocol,
    private val router: ProxyRouter,
) : Protocol {
    override val ownsFiles: Boolean get() = delegate.ownsFiles

    override suspend fun probe(request: ProbeRequest): StepResult<ProbeResult> =
        withProxy(router, request.download.id) { delegate.probe(request) }

    override fun plan(
        probe: ProbeResult,
        context: PlanContext,
    ): StepResult<TransferPlan> = delegate.plan(probe, context)

    override suspend fun transfer(
        transfer: Transfer,
        io: TransferIo,
    ): StepResult<Unit> = withProxy(router, transfer.download.id) { delegate.transfer(transfer, io) }

    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> =
        withProxy(router, transfer.download.id) { delegate.finalize(transfer) }

    override suspend fun relink(request: RelinkRequest): StepResult<Relinked> =
        withProxy(router, request.download.id) { delegate.relink(request) }

    override fun workFolders(download: Download): List<String> = delegate.workFolders(download)

    override fun setGlobalLimits(
        download: Long?,
        upload: Long?,
    ) = delegate.setGlobalLimits(download, upload)

    override suspend fun resumeSeeding(download: Download): StepResult<Unit> =
        withProxy(router, download.id) {
            delegate.resumeSeeding(download)
        }

    override suspend fun close() = delegate.close()
}
