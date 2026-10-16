package dev.anydm.engine.store

/** The database holds something this anydm can't read: a newer schema version, or a value a newer anydm wrote. */
class IncompatibleStoreException(
    message: String,
) : RuntimeException(message)
