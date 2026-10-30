package dev.anydm.engine.store

import dev.anydm.engine.ftp.FakeKeychain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Where a browser's cookies are kept (OSI-03). */
class CookieVaultTest {
    private val keychain = FakeKeychain()

    @Test
    fun cookiesGoToTheKeychainAndTheMarkerNeverHoldsThem() =
        runTest {
            val vault = CookieVault(keychain)

            val sealed = vault.seal("sid=abc; theme=dark")

            sealed.inKeychain shouldBe true
            sealed.marker.startsWith("sealed:") shouldBe true
            sealed.marker shouldNotContain "abc"
            keychain.entries.values.single() shouldBe "sid=abc; theme=dark"
        }

    @Test
    fun aMarkerOpensToTheCookiesInThisRunAndInANewOne() =
        runTest {
            val sealed = CookieVault(keychain).seal("sid=abc")

            CookieVault(keychain).open(sealed.marker) shouldBe CookieVault.Opened.Text("sid=abc")
        }

    @Test
    fun nothingStoredIsNoCookies() =
        runTest {
            CookieVault(keychain).open(null) shouldBe CookieVault.Opened.None
        }

    @Test
    fun cookiesNotSealedYetOpenAsThemselves() =
        runTest {
            // A row written before sealing: it is moved at the next start, and works until then.
            CookieVault(keychain).open("sid=old") shouldBe CookieVault.Opened.Text("sid=old")
        }

    @Test
    fun aMarkerNobodyHoldsIsLost() =
        runTest {
            CookieVault(keychain).open("sealed:0123456789abcdef") shouldBe CookieVault.Opened.Lost
        }

    @Test
    fun aKeychainThatCannotBeReachedIsNotTheSameAsCookiesThatAreGone() =
        runTest {
            val sealed = CookieVault(keychain).seal("sid=abc")
            keychain.failing = true

            CookieVault(keychain).open(sealed.marker) shouldBe CookieVault.Opened.Unavailable
            keychain.failing = false
            CookieVault(keychain).open(sealed.marker) shouldBe CookieVault.Opened.Text("sid=abc")
        }

    @Test
    fun theMarkerForLostCookiesOpensToLostWhateverTheKeychainSays() =
        runTest {
            CookieVault(keychain).open(CookieVault.LOST) shouldBe CookieVault.Opened.Lost
            keychain.failing = true
            CookieVault(keychain).open(CookieVault.LOST) shouldBe CookieVault.Opened.Lost
        }

    @Test
    fun notSavingIsToldOncePerRun() =
        runTest {
            keychain.failing = true
            var told = 0
            val vault = CookieVault(keychain).also { it.onNotSaved = { told++ } }

            repeat(5) { vault.seal("sid=$it") }

            told shouldBe 1
        }

    @Test
    fun withoutAKeychainTheCookiesLiveForThisRunOnlyAndSaysSo() =
        runTest {
            keychain.failing = true
            var told = 0
            val vault = CookieVault(keychain).also { it.onNotSaved = { told++ } }

            val sealed = vault.seal("sid=abc")

            sealed.inKeychain shouldBe false
            told shouldBe 1
            vault.open(sealed.marker) shouldBe CookieVault.Opened.Text("sid=abc")
            // A keychain that is back but never had them, and no keychain at all: gone. One that still fails: not known.
            CookieVault(keychain).open(sealed.marker) shouldBe CookieVault.Opened.Unavailable
            keychain.failing = false
            CookieVault(keychain).open(sealed.marker) shouldBe CookieVault.Opened.Lost
            CookieVault(null).open(sealed.marker) shouldBe CookieVault.Opened.Lost
        }

    @Test
    fun noKeychainAtAllIsTheSame() =
        runTest {
            val vault = CookieVault(null)

            val sealed = vault.seal("sid=abc")

            sealed.inKeychain shouldBe false
            vault.open(sealed.marker).shouldBeInstanceOf<CookieVault.Opened.Text>()
        }

    @Test
    fun aBrokenKeychainIsNotAFailureOfTheCaller() =
        runTest {
            keychain.failure = IllegalStateException("native library gone")

            CookieVault(keychain).seal("sid=abc").inKeychain shouldBe false
        }

    @Test
    fun forgettingRemovesThemEverywhere() =
        runTest {
            val vault = CookieVault(keychain)
            val sealed = vault.seal("sid=abc")

            vault.forget(sealed.marker)

            keychain.entries shouldBe emptyMap()
            vault.open(sealed.marker) shouldBe CookieVault.Opened.Lost
        }

    @Test
    fun forgettingWhatWasNeverSealedDoesNothing() =
        runTest {
            val vault = CookieVault(keychain)
            vault.forget(null)
            vault.forget("sid=plain")
            keychain.entries shouldBe emptyMap()
        }

    @Test
    fun twoSealsGetTwoMarkers() =
        runTest {
            val vault = CookieVault(keychain)
            (vault.seal("a=1").marker != vault.seal("a=1").marker) shouldBe true
        }
}
