package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.ftp.FakeKeychain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The cookies of a download are in the keychain, never in the database (OSI-03). */
abstract class CookieSealingContract : StoreTestBase() {
    private val keychain = FakeKeychain()

    private fun TestScope.storeWith(vault: CookieVault = CookieVault(keychain)): Store = newStore(vault)

    private fun cookiesInDatabase(): List<String?> = driver.texts("SELECT cookies FROM request_context ORDER BY download_id")

    private fun new(context: RequestContext?) =
        NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://example.com/a"), context = context)

    @Test
    fun theDatabaseHoldsAMarkerAndTheContextGivesTheCookiesBack() =
        runTest {
            val store = storeWith()
            val id = store.downloads.add(new(RequestContext(listOf("X-A" to "1"), referrer = "https://r.example", cookies = "sid=abc"))).id

            val row = cookiesInDatabase().single()
            row!!.startsWith("sealed:") shouldBe true
            row shouldNotContain "abc"
            val context = store.downloads.context(id)!!
            context.cookies shouldBe "sid=abc"
            context.cookiesLost shouldBe false
            context.headers shouldBe listOf("X-A" to "1")
            context.referrer shouldBe "https://r.example"
        }

    private fun headersInDatabase(): List<String?> = driver.texts("SELECT headers FROM request_context ORDER BY download_id")

    @Test
    fun aCookieHeaderIsFoldedIntoTheSealedCookiesAndNeverStoredAsAHeader() =
        runTest {
            val store = storeWith()
            val context = RequestContext(listOf("X-A" to "1", "Cookie" to "a=1", "set-cookie" to "z=9"), cookies = "b=2")
            val id = store.downloads.add(new(context)).id

            val headers = headersInDatabase().single()!!
            headers shouldNotContain "a=1"
            headers shouldNotContain "z=9"
            headers shouldBe "X-A: 1"
            cookiesInDatabase().single()!!.startsWith("sealed:") shouldBe true
            val read = store.downloads.context(id)!!
            read.cookies shouldBe "b=2; a=1"
            read.headers shouldBe listOf("X-A" to "1")
        }

    @Test
    fun aCookieHeaderAloneIsSealedToo() =
        runTest {
            val store = storeWith()
            val id = store.downloads.add(new(RequestContext(listOf("Cookie" to "a=1", "Cookie" to "b=2")))).id

            headersInDatabase().single() shouldBe ""
            store.downloads.context(id)!!.cookies shouldBe "a=1; b=2"
            keychain.entries.values.toList() shouldBe listOf("a=1; b=2")
        }

    @Test
    fun blankCookiesAreNoCookies() =
        runTest {
            val store = storeWith()
            val id = store.downloads.add(new(RequestContext(cookies = "  "))).id

            cookiesInDatabase() shouldBe listOf(null)
            store.downloads.context(id)!!.cookies shouldBe null
            keychain.entries shouldBe emptyMap()
        }

    @Test
    fun removingACollectionForgetsTheCookiesOfItsChildrenToo() =
        runTest {
            val store = storeWith()
            val parent =
                store.downloads
                    .add(
                        NewDownload(DownloadKind.DIRECT, collection = dev.anydm.engine.core.CollectionKind.PLAYLIST),
                    ).id
            store.downloads.addChildren(
                parent,
                listOf(new(RequestContext(cookies = "sid=kid"))),
                listOf(NewItem("yt", "k1", 0, null, null)),
                null,
            )
            keychain.entries.size shouldBe 1

            store.downloads.remove(parent) shouldBe true

            keychain.entries shouldBe emptyMap()
        }

    @Test
    fun lostCookiesStayLostWhenTheContextIsCopiedToAnotherDownload() =
        runTest {
            val store = storeWith()
            val id = store.downloads.add(new(RequestContext(cookies = "sid=abc"))).id
            keychain.entries.clear()
            val lost = Store(driver, StandardTestDispatcher(testScheduler), cookies = CookieVault(keychain)).downloads.context(id)!!
            lost.cookiesLost shouldBe true

            val copy = store.downloads.add(new(lost)).id

            val read = store.downloads.context(copy)!!
            read.cookiesLost shouldBe true
            read.cookies shouldBe null
        }

    @Test
    fun aKeychainThatCannotBeReachedIsNotLostCookies() =
        runTest {
            val store = storeWith()
            val id = store.downloads.add(new(RequestContext(cookies = "sid=abc"))).id
            keychain.failing = true

            val read = Store(driver, StandardTestDispatcher(testScheduler), cookies = CookieVault(keychain)).downloads.context(id)!!

            read.cookiesLost shouldBe false
            read.cookiesUnreachable shouldBe true
            read.cookies shouldBe null
        }

    @Test
    fun aContextWithoutCookiesStoresNone() =
        runTest {
            val store = storeWith()
            val id = store.downloads.add(new(RequestContext(listOf("X-A" to "1")))).id

            cookiesInDatabase() shouldBe listOf(null)
            store.downloads.context(id)!!.cookies shouldBe null
            keychain.entries shouldBe emptyMap()
        }

    @Test
    fun childrenAreSealedToo() =
        runTest {
            val store = storeWith()
            val parent =
                store.downloads
                    .add(
                        NewDownload(DownloadKind.DIRECT, collection = dev.anydm.engine.core.CollectionKind.PLAYLIST),
                    ).id
            val children =
                store.downloads.addChildren(
                    parent,
                    listOf(new(RequestContext(cookies = "sid=kid"))),
                    listOf(NewItem("yt", "k1", 0, null, null)),
                    null,
                )

            cookiesInDatabase().single()!!.startsWith("sealed:") shouldBe true
            store.downloads.context(children.single().id)!!.cookies shouldBe "sid=kid"
        }

    @Test
    fun replacingTheContextForgetsTheOldCookies() =
        runTest {
            val store = storeWith()
            val id = store.downloads.add(new(RequestContext(cookies = "sid=one"))).id

            store.downloads.setContext(id, RequestContext(cookies = "sid=two")) shouldBe true

            keychain.entries.values.toList() shouldBe listOf("sid=two")
            store.downloads.context(id)!!.cookies shouldBe "sid=two"
            store.downloads.setContext(id, null) shouldBe true
            keychain.entries shouldBe emptyMap()
        }

    @Test
    fun removingTheDownloadForgetsItsCookies() =
        runTest {
            val store = storeWith()
            val id = store.downloads.add(new(RequestContext(cookies = "sid=abc"))).id

            store.downloads.remove(id) shouldBe true

            keychain.entries shouldBe emptyMap()
        }

    @Test
    fun cookiesAlreadyInTheDatabaseAreMovedToTheKeychain() =
        runTest {
            val store = storeWith()
            val id = store.downloads.add(new(RequestContext(listOf("X-A" to "1")))).id
            driver.exec("UPDATE request_context SET cookies = 'sid=old' WHERE download_id = ${id.value}")

            store.sealLegacyCookies() shouldBe 1

            val row = cookiesInDatabase().single()
            row!!.startsWith("sealed:") shouldBe true
            row shouldNotContain "old"
            keychain.entries.values.toList() shouldBe listOf("sid=old")
            store.downloads.context(id)!!.cookies shouldBe "sid=old"
            // Nothing left to move.
            store.sealLegacyCookies() shouldBe 0
        }

    @Test
    fun withoutAKeychainTheOldCookiesStayWhereTheyAre() =
        runTest {
            keychain.failing = true
            val store = storeWith()
            val id = store.downloads.add(new(RequestContext(listOf("X-A" to "1")))).id
            driver.exec("UPDATE request_context SET cookies = 'sid=old' WHERE download_id = ${id.value}")

            store.sealLegacyCookies() shouldBe 0

            cookiesInDatabase() shouldBe listOf("sid=old")
            store.downloads.context(id)!!.cookies shouldBe "sid=old"
        }

    @Test
    fun aRestartWithoutTheKeychainLosesTheCookiesAndSaysSo() =
        runTest {
            keychain.failing = true
            val first = storeWith()
            val id = first.downloads.add(new(RequestContext(listOf("X-A" to "1"), cookies = "sid=abc"))).id
            first.downloads.context(id)!!.cookies shouldBe "sid=abc"

            // The same database, a new run: nobody holds the cookies any more.
            // No keychain at all this time: nothing can hold the cookies, and nothing will.
            val second = Store(driver, StandardTestDispatcher(testScheduler), cookies = CookieVault(null))

            val context = second.downloads.context(id)!!
            context.cookies shouldBe null
            context.cookiesLost shouldBe true
            context.headers shouldBe listOf("X-A" to "1")
        }
}
