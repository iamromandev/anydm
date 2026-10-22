# cross/

The anydm desktop client: Kotlin Multiplatform, with Compose for the window. It downloads on its
own, with the engine in `shared/`; no server is needed.

- `shared/` — the download engine, being built in #548–#556, in four packages:
  `dev.anydm.engine.core` (job model, states, step runner, queue), `dev.anydm.engine.protocol` (the
  `Protocol` interface each kind of source implements), `dev.anydm.engine.store` (SQLite through
  SQLDelight) and `dev.anydm.engine.platform` (files, app folders, clock). Only the `jvm()` target
  for now; Android and iOS would add targets here. Source sets:
  - `commonMain` is pure Kotlin. With only a JVM target the compiler can't enforce that, so the
    `checkCommonApis` task (part of `check`) is a heuristic: it rejects `java.`/`javax.` references
    and `System`/`Thread`/`Runtime`/`Math`/`ProcessBuilder` use in `commonMain` and `commonTest`
    (comments are ignored).
  - `jvmCommonMain` holds code that needs JVM libraries. `jvm` depends on it now and Android will
    later, so nothing desktop-only (`java.awt`, Swing) goes there. Until an Android target exists
    to enforce that, the `checkJvmCommonApis` task (part of `check`) fails on such references.
  - `jvmMain` is desktop only.
  - Each has a matching `*Test` source set.
  - Platform code (`dev.anydm.engine.platform`) is interfaces in `commonMain` with desktop versions
    in `jvmMain`. Paths are `kotlinx.io.files.Path`, and expected failures come back as a
    `FileResult`. Engine code takes a `kotlin.time.Clock`: `Clock.System` in the app,
    `virtualClock()` (on `runTest`'s virtual time) in tests. `FileSystem` has an abstract contract
    test in `commonTest` that every implementation's test extends.
  - The store (`dev.anydm.engine.store`) is SQLite through SQLDelight: tables and queries in
    `src/commonMain/sqldelight/dev/anydm/engine/store/db/*.sq`, repositories in `commonMain`, and the JDBC driver in
    `jvmMain` (`openStore` opens `anydm.db` in the data folder). Ids are never reused, enums are stored by name and times
    as epoch milliseconds. To change the schema, edit the `.sq` file, add `N.sqm` with the SQL from version N to N+1,
    run `./gradlew :shared:generateCommonMainAnydmDbSchema` to add `(N+1).db`, and commit all three. `check` runs
    `verifyCommonMainAnydmDbMigration`, which fails if the migrations don't reach the `.sq` schema. Never edit a committed `N.db`.
    Migrations run with foreign keys off and are checked with `foreign_key_check` before commit, so a table rebuild is safe.
  - `StepRunner` runs a job's steps with a `Protocol`: `prepare` (preflight and probe, PENDING to QUEUED) and
    `run` (transfer, post-process, finish). It moves the job with `transitionTo` and reports each move to a
    `RunListener` instead of saving it, and writes only the plan's files and segments. Bytes go into a sparse
    `<name>.<download id>.anydm` partial that is renamed into place at the end (never over a file that is already
    there) and kept on failure or cancel, so the next run can resume it. A run resumes only when the planned file list
    equals the stored one, and then only the files whose ranges and partial still match; anything else starts over.
    A QUEUED job without the in-memory `Ready` from `prepare` must go back through PENDING. Tests script jobs with
    `FakeProtocol`, `FakeFileSystem` and `InMemoryPlanStore` from `commonTest`.
  - Bonding (#544) splits one download across networks. `Bonding` keeps the networks (`NetworkLister`), which the user turned
    on (`Engine.setNetworkEnabled`, saved in the settings) and whether a connection can be tied to each (`NetworkBinder`); it is
    on with two or more in use, and with fewer nothing is bound. HTTP workers each take a network, the next one going to the
    network furthest below its share of the workers, the shares following the measured speed; FTP and SFTP downloads are bound
    whole to one network; a torrent session sends from the bonded interfaces (`outgoing_interfaces`, one list for all its
    torrents). A download through a proxy is never bonded, and a connection that cannot be bound fails: it is never made on the
    default route. When the networks in use change, running connections start again. The desktop ties sockets with `IP_BOUND_IF`
    (macOS), `IP_UNICAST_IF` (Windows) or `SO_BINDTODEVICE` (Linux) through JNA, on the socket's native handle, which JDK 21 gives
    only by reflection: the app and the tests start with `--add-opens java.base/java.net` and `java.base/java.io`. Without the
    right to bind a device, Linux binds the network's address instead; that keeps the traffic on the network only if the host
    routes by source address, so such a network is reported as `BEST_EFFORT`. DNS lookups are not bound. Media (`SITE`) downloads
    are not bonded: the URLs yt-dlp extracts are tied to the address that asked. `-Pbonding.live` runs the opt-in test against
    real networks (needs two).
  - OS integration (#545, part 1; the clipboard watch and the global hotkey are part 2) is the services any UI uses, in
    `dev.anydm.engine.os`. `Engine.addLink` is the one intake: a web, FTP or torrent address, a `magnet:` link, a `.torrent`
    file or an `anydm://add?url=` link goes into the queue at once with the default profile, and an `IncomingLink` event says
    so; `StartupArgs` sends program arguments through it. `BridgeServer` (the JDK's own HTTP server, so the packaged runtime
    adds `jdk.httpserver`) is the browser extension's door: `127.0.0.1` only, on the first free port of 18400-18409
    (`GET /ping` tells which is anydm), `Host` and `Origin` checked (an extension origin, narrowed by
    `Engine.setBridgeOrigins`), JSON only, 64 KB at most. `POST /add` reads the link with `Engine.probeLink` and answers
    with a token valid 15 s; `POST /confirm` redeems it once. A browser's cookies are sealed: the keychain holds the text
    (`CookieVault`, via `openStore(keychain = ...)`) and the row holds a `sealed:` marker, so the database has no cookies; old
    rows are moved at the next start; with no keychain they live in memory for the run, and after a restart the download
    fails as needing a login. `FileSingleInstance` keeps one app per user: a lock file plus a loopback socket with a secret in
    the data folder, and a second launch hands its arguments over and exits. `NotificationRelay` turns finished, failed and
    allowance-reached events into notifications through a `Notifier` (`notifierForCurrentOs`); `Engine.setNotifications`
    switches them. Nothing here registers `magnet:` or `anydm://` with the OS; that is packaging.
- `desktopApp/` — the Compose Desktop app, in a native look: a toolbar in the title bar, a sidebar
  with counts, rows with hover actions and right-click menus, selection and keyboard control, a
  menu bar, a Settings window, and a tray. It saves finished files into Downloads and plays them in
  the system's or a chosen player. `make cross-package` builds an unsigned dmg / msi / deb. On
  macOS with a Homebrew JDK, Compose refuses to package unless you add
  `compose.desktop.packaging.checkJdkVendor=false` to your own `~/.gradle/gradle.properties`.

The app icon is drawn by `desktopApp/icons/make_icons.py`; after changing it, run
`uv run --no-project --with pillow python icons/make_icons.py` from `desktopApp/`.

Needs JDK 21. The Gradle wrapper is committed, so there is nothing else to install.

```bash
make cross-check     # ktlint, tests, compile
make cross-run       # open the app
make cross-package   # dmg / msi / deb for this OS
```
