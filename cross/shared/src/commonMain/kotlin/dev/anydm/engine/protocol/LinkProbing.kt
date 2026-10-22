package dev.anydm.engine.protocol

import dev.anydm.engine.core.RequestContext

/** What a probe of a link found (OSI-02): enough for a person to confirm adding it. */
data class LinkProbe(
    val name: String?,
    val size: Long?,
    val rangesSupported: Boolean,
)

/**
 * A protocol that can look at a link without a job behind it (OSI-02), as the browser bridge does before it asks the user
 * to confirm. It reads what [Protocol.probe] reads, sends the same requests, and stores nothing.
 */
interface LinkProbing {
    suspend fun probeLink(
        url: String,
        context: RequestContext?,
    ): StepResult<LinkProbe>
}
