package dev.anydm.engine.http

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class CredentialsTest {
    @Test
    fun keyedByOrigin() =
        runTest {
            val store = InMemoryCredentials()
            store.set("https://H/x", Credentials("u", "p"))
            store.credentialsFor(originOf("https://h:443/f.zip")) shouldBe Credentials("u", "p")
            store.credentialsFor(originOf("http://h/f.zip")).shouldBeNull()
            store.remove("https://h")
            store.credentialsFor(originOf("https://h/")).shouldBeNull()
        }

    @Test
    fun userInfo() {
        userInfoOf("https://u%C3%BC:p%40ss@h/f") shouldBe Credentials("uü", "p@ss")
        userInfoOf("https://u@h/f") shouldBe Credentials("u", "")
        userInfoOf("https://u:p:q@h:8080?x=1") shouldBe Credentials("u", "p:q")
        userInfoOf("https://h/f").shouldBeNull()
        userInfoOf("https://h/a@b").shouldBeNull()
        userInfoOf("https://a%3Ab:p@h/f").shouldBeNull()
    }
}
