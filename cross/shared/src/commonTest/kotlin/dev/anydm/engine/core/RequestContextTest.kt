package dev.anydm.engine.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

class RequestContextTest {
    @Test
    fun toStringHidesCookiesAndValues() {
        val context = RequestContext(listOf("Authorization" to "Bearer hunter2"), "https://ref.test/", "UA-secret", "session=hunter3")

        val text = context.toString()

        text shouldNotContain "hunter2"
        text shouldNotContain "hunter3"
        text shouldNotContain "UA-secret"
        text shouldContain "Authorization"
        text shouldContain "cookies=***"
    }

    @Test
    fun noCookiesShowAsNull() {
        RequestContext().toString() shouldContain "cookies=null"
        (RequestContext() == RequestContext()) shouldBe true
    }
}
