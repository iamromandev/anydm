package dev.anydm.engine.torrent

/**
 * How the libtorrent session is set up. [port] is asked once, when the session starts: a new port takes effect with the
 * next start of the engine. [loopbackOnly] listens on 127.0.0.1 alone and turns off DHT, local discovery, UPnP and
 * NAT-PMP, for tests and for a machine with no network; [peerHints] are `host:port` peers every torrent and magnet link is
 * also told about (a swarm with no tracker, in tests).
 */
class TorrentConfig(
    val port: suspend () -> Int,
    val loopbackOnly: Boolean = false,
    val peerHints: List<String> = emptyList(),
)
