package dev.anydm.engine.core

/**
 * Why a PAUSED [Download] is paused. A job holds a set of them, and an automatic resume removes only its own, so a
 * job paused for two reasons waits for both (Download.release). The queue (#552) sets [USER] and [GLOBAL]; the others
 * belong to the features that pause on their own. Stored by name.
 */
enum class PauseReason {
    /** The user paused this job. */
    USER,

    /** Global pause is on (FND-05). */
    GLOBAL,

    /** Too little free space (FIL-08). */
    LOW_DISK,

    /** The network went away (NET-13). */
    NETWORK,

    /** A runtime tool it needs is being installed (BIN-01). */
    TOOLS,

    /** Outside the allowed hours or over the allowance (NET-07, NET-08). */
    SCHEDULE,
}
