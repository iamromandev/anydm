package dev.anydm.api

import io.ktor.client.engine.cio.CIO

/**
 * The desktop's client: Ktor's CIO engine. Its own 15-second request limit is off, so the
 * client's [HttpTimeout][io.ktor.client.plugins.HttpTimeout] rules instead: 30 s for a call,
 * none for the event stream, which the engine's limit used to cut and reopen every 15 s.
 */
fun createAnydmApi(config: ServerConfig): AnydmApi = AnydmApi(config, CIO.create { requestTimeout = 0 })
