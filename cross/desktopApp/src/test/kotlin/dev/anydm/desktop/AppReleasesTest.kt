package dev.anydm.desktop

import dev.anydm.engine.http.HttpIoException
import dev.anydm.engine.http.HttpIoKind
import dev.anydm.engine.tools.Arch
import dev.anydm.engine.tools.OsFamily
import dev.anydm.engine.tools.Platform
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class AppReleasesTest {
    private val repo = "iamromandev/anydm"
    private val api = "https://api.github.com/repos/$repo/releases?per_page=10"
    private val mac = Platform(OsFamily.MACOS, Arch.ARM64)
    private val intelMac = Platform(OsFamily.MACOS, Arch.X64)
    private val windows = Platform(OsFamily.WINDOWS, Arch.X64)
    private val linux = Platform(OsFamily.LINUX, Arch.X64)

    private fun hash(c: Char) = c.toString().repeat(64)

    private fun url(
        tag: String,
        name: String,
    ) = "https://github.com/$repo/releases/download/$tag/$name"

    private fun asset(
        tag: String,
        name: String,
        url: String = url(tag, name),
        size: Long = 1000,
    ) = """{"name":${q(name)},"browser_download_url":${q(url)},"size":$size}"""

    private fun q(text: String) = JsonPrimitive(text).toString()

    private fun release(
        tag: String,
        assets: List<String>,
        prerelease: Boolean = false,
        draft: Boolean = false,
    ) = """{"tag_name":"$tag","prerelease":$prerelease,"draft":$draft,"assets":[${assets.joinToString(",")}]}"""

    private fun standardAssets(tag: String): List<String> {
        val v = tag.removePrefix("v")
        return listOf(
            asset(tag, "anydm-$v.dmg"),
            asset(tag, "anydm-$v.msi"),
            asset(tag, "anydm_$v-1_amd64.deb"),
            asset(tag, "SHA2-256SUMS"),
        )
    }

    private fun standardSums(tag: String): Answer {
        val v = tag.removePrefix("v")
        return Answer("${hash('a')}  anydm-$v.dmg\n${hash('b')} *anydm-$v.msi\n${hash('c')}  anydm_$v-1_amd64.deb\n")
    }

    private fun http(
        releases: List<String>,
        vararg sumsFor: Pair<String, Answer>,
    ) = FakeHttp(
        mutableMapOf(api to Answer("[${releases.joinToString(",")}]")).also { routes ->
            for ((tag, answer) in sumsFor) routes[url(tag, "SHA2-256SUMS")] = answer
        },
    )

    @Test
    fun `the installer for each system is picked by its extension with its checksum from the sums`() =
        runTest {
            val http = http(listOf(release("v1.2.0", standardAssets("v1.2.0"))), "v1.2.0" to standardSums("v1.2.0"))

            val dmg = assertIs<ReleaseCheck.Found>(AppReleases(http, mac).latest()).release
            val msi = assertIs<ReleaseCheck.Found>(AppReleases(http, windows).latest()).release
            val deb = assertIs<ReleaseCheck.Found>(AppReleases(http, linux).latest()).release

            assertEquals(Version.parse("1.2.0"), dmg.version)
            assertEquals("https://github.com/$repo/releases/tag/v1.2.0", dmg.pageUrl)
            assertEquals(AppAsset("anydm-1.2.0.dmg", url("v1.2.0", "anydm-1.2.0.dmg"), 1000, hash('a')), dmg.asset)
            assertEquals(AppAsset("anydm-1.2.0.msi", url("v1.2.0", "anydm-1.2.0.msi"), 1000, hash('b')), msi.asset)
            assertEquals(AppAsset("anydm_1.2.0-1_amd64.deb", url("v1.2.0", "anydm_1.2.0-1_amd64.deb"), 1000, hash('c')), deb.asset)
        }

    @Test
    fun `the request goes to the api of the anydm repository over https`() =
        runTest {
            val http = http(listOf(release("v1.2.0", standardAssets("v1.2.0"))), "v1.2.0" to standardSums("v1.2.0"))

            AppReleases(http, mac).latest()

            assertEquals(api, http.requests.first().url)
            assertTrue(http.requests.all { it.url.startsWith("https://") })
        }

    @Test
    fun `the highest stable version wins whatever the order`() =
        runTest {
            val http =
                http(
                    listOf(
                        release("v1.1.0", standardAssets("v1.1.0")),
                        release("v1.10.0", standardAssets("v1.10.0")),
                        release("v1.9.0", standardAssets("v1.9.0")),
                    ),
                    "v1.10.0" to standardSums("v1.10.0"),
                )

            val found = assertIs<ReleaseCheck.Found>(AppReleases(http, mac).latest())

            assertEquals(Version.parse("1.10.0"), found.release.version)
        }

    @Test
    fun `prereleases, drafts, pre-release tags and garbage tags are skipped`() =
        runTest {
            val http =
                http(
                    listOf(
                        release("v3.0.0", standardAssets("v3.0.0"), prerelease = true),
                        release("v2.9.0", standardAssets("v2.9.0"), draft = true),
                        release("v2.8.0-rc.1", standardAssets("v2.8.0-rc.1")),
                        release("nightly", standardAssets("nightly")),
                        release("v1.0.1", standardAssets("v1.0.1")),
                    ),
                    "v1.0.1" to standardSums("v1.0.1"),
                )

            val found = assertIs<ReleaseCheck.Found>(AppReleases(http, mac).latest())

            assertEquals("v1.0.1", found.release.tag)
        }

    @Test
    fun `only prereleases means no release`() =
        runTest {
            val http = http(listOf(release("v3.0.0", standardAssets("v3.0.0"), prerelease = true)))

            assertEquals(ReleaseCheck.None, AppReleases(http, mac).latest())
            assertEquals(ReleaseCheck.None, AppReleases(http(emptyList()), mac).latest())
        }

    @Test
    fun `a release without an installer for this system cannot be installed`() =
        runTest {
            val assets = listOf(asset("v1.2.0", "anydm-1.2.0.dmg"), asset("v1.2.0", "SHA2-256SUMS"))
            val http = http(listOf(release("v1.2.0", assets)), "v1.2.0" to standardSums("v1.2.0"))

            val check = assertIs<ReleaseCheck.CannotInstall>(AppReleases(http, linux).latest())

            assertEquals(Version.parse("1.2.0"), check.version)
            assertEquals("https://github.com/$repo/releases/tag/v1.2.0", check.pageUrl)
        }

    @Test
    fun `a release without a sums asset cannot be installed`() =
        runTest {
            val assets = standardAssets("v1.2.0").filterNot { "SHA2-256SUMS" in it }
            val http = http(listOf(release("v1.2.0", assets)))

            assertIs<ReleaseCheck.CannotInstall>(AppReleases(http, mac).latest())
            assertTrue(http.requests.none { it.url.endsWith("SHA2-256SUMS") })
        }

    @Test
    fun `an installer missing from the sums cannot be installed`() =
        runTest {
            val http = http(listOf(release("v1.2.0", standardAssets("v1.2.0"))), "v1.2.0" to Answer("${hash('a')}  something-else.dmg\n"))

            assertIs<ReleaseCheck.CannotInstall>(AppReleases(http, mac).latest())
        }

    @Test
    fun `an asset whose address leaves the release is ignored`() =
        runTest {
            val evil =
                listOf(
                    "https://evil.example/iamromandev/anydm/releases/download/v1.2.0/anydm-1.2.0.dmg",
                    "https://github.com.evil.example/$repo/releases/download/v1.2.0/anydm-1.2.0.dmg",
                    "http://github.com/$repo/releases/download/v1.2.0/anydm-1.2.0.dmg",
                    "https://github.com/someone/else/releases/download/v1.2.0/anydm-1.2.0.dmg",
                    "https://github.com/$repo/releases/download/v0.0.1/anydm-1.2.0.dmg",
                    "https://github.com/$repo/releases/download/v1.2.0/other.dmg",
                    "https://github.com@evil.example/$repo/releases/download/v1.2.0/anydm-1.2.0.dmg",
                )
            for (bad in evil) {
                val assets = listOf(asset("v1.2.0", "anydm-1.2.0.dmg", url = bad), asset("v1.2.0", "SHA2-256SUMS"))
                val http = http(listOf(release("v1.2.0", assets)), "v1.2.0" to standardSums("v1.2.0"))

                assertIs<ReleaseCheck.CannotInstall>(AppReleases(http, mac).latest(), bad)
            }
        }

    @Test
    fun `a sums asset on another host is not trusted`() =
        runTest {
            val bad = "https://evil.example/$repo/releases/download/v1.2.0/SHA2-256SUMS"
            val assets = listOf(asset("v1.2.0", "anydm-1.2.0.dmg"), asset("v1.2.0", "SHA2-256SUMS", url = bad))
            val http = http(listOf(release("v1.2.0", assets)), "v1.2.0" to standardSums("v1.2.0"))
            http.routes[bad] = standardSums("v1.2.0")

            assertIs<ReleaseCheck.CannotInstall>(AppReleases(http, mac).latest())
            assertTrue(http.requests.none { "evil" in it.url })
        }

    @Test
    fun `asset names that could leave the download folder are refused`() =
        runTest {
            val names = listOf("../../evil.dmg", "..\\evil.dmg", "a/b.dmg", ".dmg", "sub/../x.dmg", "x y.dmg", "x\u0000.dmg", "-rf.dmg")
            for (name in names) {
                val assets = listOf(asset("v1.2.0", name), asset("v1.2.0", "SHA2-256SUMS"))
                val http = http(listOf(release("v1.2.0", assets)), "v1.2.0" to Answer("${hash('a')}  $name\n"))

                assertIs<ReleaseCheck.CannotInstall>(AppReleases(http, mac).latest(), name)
            }
        }

    @Test
    fun `the architecture in a name picks between installers`() =
        runTest {
            val tag = "v1.2.0"
            val names = listOf("anydm_1.2.0-1_amd64.deb", "anydm_1.2.0-1_arm64.deb")
            val assets = names.map { asset(tag, it) } + asset(tag, "SHA2-256SUMS")
            val sumsText = Answer("${hash('a')}  ${names[0]}\n${hash('b')}  ${names[1]}\n")

            suspend fun pick(platform: Platform) =
                assertIs<ReleaseCheck.Found>(
                    AppReleases(http(listOf(release(tag, assets)), tag to sumsText), platform).latest(),
                ).release.asset.name

            assertEquals(names[0], pick(Platform(OsFamily.LINUX, Arch.X64)))
            assertEquals(names[1], pick(Platform(OsFamily.LINUX, Arch.ARM64)))
        }

    @Test
    fun `an installer for another architecture is not offered`() =
        runTest {
            val tag = "v1.2.0"
            val only = listOf(asset(tag, "anydm-1.2.0-arm64.dmg"), asset(tag, "SHA2-256SUMS"))
            val sumsText = Answer("${hash('a')}  anydm-1.2.0-arm64.dmg\n")

            assertIs<ReleaseCheck.CannotInstall>(
                AppReleases(http(listOf(release(tag, only)), tag to sumsText), intelMac).latest(),
            )
            assertEquals(
                "anydm-1.2.0-arm64.dmg",
                assertIs<ReleaseCheck.Found>(
                    AppReleases(http(listOf(release(tag, only)), tag to sumsText), mac).latest(),
                ).release.asset.name,
            )
        }

    // The dmg is built on an Apple silicon runner and its name carries no architecture: an Intel Mac must not be handed it.
    @Test
    fun `a dmg without an architecture is refused on an Intel Mac and offered on Apple silicon`() =
        runTest {
            val http = http(listOf(release("v1.2.0", standardAssets("v1.2.0"))), "v1.2.0" to standardSums("v1.2.0"))

            val refused = assertIs<ReleaseCheck.CannotInstall>(AppReleases(http, intelMac).latest())
            assertEquals(Version.parse("1.2.0"), refused.version)
            assertEquals("https://github.com/$repo/releases/tag/v1.2.0", refused.pageUrl)
            assertEquals("anydm-1.2.0.dmg", assertIs<ReleaseCheck.Found>(AppReleases(http, mac).latest()).release.asset.name)
        }

    @Test
    fun `a dmg made for Intel is still offered on an Intel Mac`() =
        runTest {
            val tag = "v1.2.0"
            val names = listOf("anydm-1.2.0.dmg", "anydm-1.2.0-x64.dmg")
            val assets = names.map { asset(tag, it) } + asset(tag, "SHA2-256SUMS")
            val sumsText = Answer("${hash('a')}  ${names[0]}\n${hash('b')}  ${names[1]}\n")

            val found = assertIs<ReleaseCheck.Found>(AppReleases(http(listOf(release(tag, assets)), tag to sumsText), intelMac).latest())

            assertEquals(names[1], found.release.asset.name)
        }

    @Test
    fun `an installer without an architecture is still offered on Windows and Linux`() =
        runTest {
            val tag = "v1.2.0"
            val assets = listOf(asset(tag, "anydm-1.2.0.msi"), asset(tag, "anydm-1.2.0.deb"), asset(tag, "SHA2-256SUMS"))
            val sumsText = Answer("${hash('a')}  anydm-1.2.0.msi\n${hash('b')}  anydm-1.2.0.deb\n")
            val http = http(listOf(release(tag, assets)), tag to sumsText)

            assertIs<ReleaseCheck.Found>(AppReleases(http, windows).latest())
            assertIs<ReleaseCheck.Found>(AppReleases(http, linux).latest())
        }

    @Test
    fun `failures to read the releases are a failed check`() =
        runTest {
            val ok = http(listOf(release("v1.2.0", standardAssets("v1.2.0"))), "v1.2.0" to standardSums("v1.2.0"))

            val serverError = FakeHttp(mutableMapOf(api to Answer(status = 503, body = { FakeBody(ByteArray(0)) })))
            val garbage = FakeHttp(mutableMapOf(api to Answer("not json")))
            val notAList = FakeHttp(mutableMapOf(api to Answer("""{"message":"rate limited"}""")))
            val offline =
                object : dev.anydm.engine.http.HttpTransport {
                    override suspend fun open(request: dev.anydm.engine.http.HttpRequest) =
                        throw HttpIoException(HttpIoKind.CONNECT, "api.github.com")
                }
            val noSums = http(listOf(release("v1.2.0", standardAssets("v1.2.0"))))
            val redirected = FakeHttp(mutableMapOf(api to Answer("[]", finalUrl = "https://evil.example/releases")))
            val downgraded =
                http(
                    listOf(release("v1.2.0", standardAssets("v1.2.0"))),
                    "v1.2.0" to Answer("x", finalUrl = "http://github.com/sums"),
                )

            assertIs<ReleaseCheck.Found>(AppReleases(ok, mac).latest())
            for (broken in listOf(serverError, garbage, notAList, noSums, redirected, downgraded)) {
                assertEquals(ReleaseCheck.Failed, AppReleases(broken, mac).latest())
            }
            assertEquals(ReleaseCheck.Failed, AppReleases(offline, mac).latest())
        }

    @Test
    fun `a release that is not an object is skipped`() =
        runTest {
            val http =
                FakeHttp(
                    mutableMapOf(
                        api to Answer("""[1, null, "x", {"tag_name": 5}, ${release("v1.0.1", standardAssets("v1.0.1"))}]"""),
                        url("v1.0.1", "SHA2-256SUMS") to standardSums("v1.0.1"),
                    ),
                )

            assertEquals("v1.0.1", assertIs<ReleaseCheck.Found>(AppReleases(http, mac).latest()).release.tag)
        }

    @Test
    fun `a name listed with two different sums is not trusted`() =
        runTest {
            val sums = Answer("${hash('a')}  anydm-1.2.0.dmg\n${hash('b')}  anydm-1.2.0.dmg\n")
            val http = http(listOf(release("v1.2.0", standardAssets("v1.2.0"))), "v1.2.0" to sums)

            assertIs<ReleaseCheck.CannotInstall>(AppReleases(http, mac).latest())
        }

    @Test
    fun `a name listed twice with the same sum is still trusted`() =
        runTest {
            val sums = Answer("${hash('a')}  anydm-1.2.0.dmg\n${hash('a')} *anydm-1.2.0.dmg\n")
            val http = http(listOf(release("v1.2.0", standardAssets("v1.2.0"))), "v1.2.0" to sums)

            assertEquals(hash('a'), assertIs<ReleaseCheck.Found>(AppReleases(http, mac).latest()).release.asset.sha256)
        }

    @Test
    fun `sums with windows line endings are read`() =
        runTest {
            val sums = Answer("${hash('a')}  anydm-1.2.0.dmg\r\n${hash('b')} *anydm-1.2.0.msi\r\n")
            val http = http(listOf(release("v1.2.0", standardAssets("v1.2.0"))), "v1.2.0" to sums)

            assertEquals(hash('a'), assertIs<ReleaseCheck.Found>(AppReleases(http, mac).latest()).release.asset.sha256)
            assertEquals(hash('b'), assertIs<ReleaseCheck.Found>(AppReleases(http, windows).latest()).release.asset.sha256)
        }

    @Test
    fun `sums over one mebibyte are not read`() =
        runTest {
            val padding = "#".repeat((1 shl 20) + 1)
            val http =
                http(
                    listOf(release("v1.2.0", standardAssets("v1.2.0"))),
                    "v1.2.0" to Answer("${hash('a')}  anydm-1.2.0.dmg\n$padding"),
                )

            assertEquals(ReleaseCheck.Failed, AppReleases(http, mac).latest())
        }

    @Test
    fun `a release that does not say whether it is a draft or a prerelease is skipped`() =
        runTest {
            val assets = standardAssets("v1.2.0").joinToString(",")
            val noDraft = """{"tag_name":"v1.2.0","prerelease":false,"assets":[$assets]}"""
            val noPrerelease = """{"tag_name":"v1.2.0","draft":false,"assets":[$assets]}"""
            val neither = """{"tag_name":"v1.2.0","assets":[$assets]}"""
            val oddDraft = """{"tag_name":"v1.2.0","draft":"no","prerelease":false,"assets":[$assets]}"""

            for (odd in listOf(noDraft, noPrerelease, neither, oddDraft)) {
                val http = http(listOf(odd), "v1.2.0" to standardSums("v1.2.0"))

                assertEquals(ReleaseCheck.None, AppReleases(http, mac).latest(), odd)
            }
        }

    @Test
    fun `only the hosts a release is served from count as github`() {
        val yes =
            listOf(
                "https://github.com/iamromandev/anydm/releases/download/v1/a.dmg",
                "https://api.github.com/repos/x/y/releases",
                "https://objects.githubusercontent.com/github-production-release-asset/1",
                "https://release-assets.githubusercontent.com/github-production-release-asset/1?x=y",
                "https://github-releases.githubusercontent.com/1/2",
                "https://GitHub.com/x",
                "https://github.com:443/x",
            )
        val no =
            listOf(
                "http://github.com/x",
                "HTTPS://github.com/x",
                "ftp://github.com/x",
                "//github.com/x",
                "github.com/x",
                "https://github.com@evil.example/x",
                "https://user:pass@github.com/x",
                "https://user@github.com/x",
                "https://github.com:8443/x",
                "https://github.com:80/x",
                "https://github.com./x",
                "https://github.com.evil.com/x",
                "https://evilgithub.com/x",
                "https://evil.example/github.com/x",
                "https://raw.githubusercontent.com/iamromandev/anydm/main/x",
                "https://gist.github.com/x",
                "https://gist.githubusercontent.com/x",
                "https://www.github.com/x",
                "https://githubusercontent.com/x",
                "https://objects.githubusercontent.com.evil.com/x",
                "https://evil.objects.githubusercontent.com/x",
                "https://github.com\\@evil.example/x",
                "https://github.com /x",
                "",
            )
        for (url in yes) assertTrue(isGithubHttps(url), url)
        for (url in no) assertTrue(!isGithubHttps(url), url)
    }
}
