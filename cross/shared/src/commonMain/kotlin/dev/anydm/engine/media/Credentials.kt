package dev.anydm.engine.media

import dev.anydm.engine.core.RequestContext

/** Header names that carry a login: they go only to the host they were meant for. */
internal val CREDENTIAL_HEADERS = setOf("authorization", "proxy-authorization", "cookie")

/** This context as it may go to a host that was not named for it: no login headers, no cookies. */
internal fun RequestContext.withoutCredentials(): RequestContext =
    RequestContext(headers.filterNot { it.first.lowercase() in CREDENTIAL_HEADERS }, referrer, userAgent, null)
