# cross/

The anydm desktop client: Kotlin Multiplatform, with Compose for the window. It is an empty skeleton
for now: a blank window and no features.

- `shared/` — common code (`dev.anydm.engine`), `jvm()` target only. Android and iOS would add
  targets here.
- `desktopApp/` — the Compose desktop window (`dev.anydm.desktop.MainKt`) and its installers.

JDK 25, through the Gradle wrapper:

- `make cross-check` — ktlint, tests and compile (`./gradlew check`).
- `make cross-test` — the shared and desktop tests.
- `make cross-run` — open the window.
- `make cross-package` — build this OS's installer (dmg / msi / deb).

With a Homebrew JDK, packaging stops at `checkRuntime`; pass
`-Pcompose.desktop.packaging.checkJdkVendor=false` or use another vendor's JDK.

## Releasing

`.github/workflows/release.yml` builds the installers and publishes them as a GitHub release:

1. Set `appVersion` in `desktopApp/build.gradle.kts` to `X.Y.Z` and merge that.
2. Tag the merge commit `vX.Y.Z` (or `vX.Y.Z-rc.N` for a prerelease) and push the tag, only on a
   commit whose CI run is green: the release workflow does not run the tests.

The workflow fails early if the tag does not match `appVersion`. It builds
`:desktopApp:packageDistributionForCurrentOS` on pinned macOS, Windows and Ubuntu runners and
publishes `anydm-X.Y.Z.dmg`, `anydm-X.Y.Z.msi`, `anydm_X.Y.Z-1_amd64.deb` and `SHA2-256SUMS` to a
release that stays a draft until every asset is up. The installers are not code-signed or notarized.
