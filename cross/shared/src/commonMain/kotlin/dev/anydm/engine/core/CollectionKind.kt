package dev.anydm.engine.core

/**
 * The kind of collection a [Download] stands for when its `collection` is set. The children of a
 * collection point back at it through `parentId`.
 */
enum class CollectionKind {
    /** The entries of a playlist. */
    PLAYLIST,

    /** The uploads of a channel. */
    CHANNEL,

    /** A set of links added together. */
    BATCH,
}
