package dev.anydm.engine.core

/** The steps of one job, in order (FND-03). */
enum class Step {
    /** Runs in PENDING: the save folder is writable, there is room, and the job is no duplicate. */
    PREFLIGHT,

    /** Runs in PENDING: asks the source what there is. */
    PROBE,

    /** Runs in DOWNLOADING: plans, stores the plan, opens the partials and fetches. */
    TRANSFER,

    /** Runs in MUXING: the protocol's finalize, then the rename of each output into place. */
    POST_PROCESS,

    /** Runs in COMPLETED or SEEDING: the finish hook. It can't fail. */
    FINISH,
}
