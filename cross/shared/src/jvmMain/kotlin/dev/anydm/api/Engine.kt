package dev.anydm.api

import io.ktor.client.engine.cio.CIO

/** The desktop's client: Ktor's CIO engine. */
fun createAnydmApi(config: ServerConfig): AnydmApi = AnydmApi(config, CIO.create())
