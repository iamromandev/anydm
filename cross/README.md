# cross/

The anydm desktop client: Kotlin Multiplatform, with Compose for the window. It downloads on its
own, with the engine in `shared/`; no server is needed.

- `shared/` — the download engine (epic #536, complete), in four packages:
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
  - A job the probe finds to be a duplicate fails with `ErrorClass.DUPLICATE` and is never retried by itself;
    `Engine.retry(id, allowDuplicate = true)` sends it back past the duplicate check (the UI's "Download anyway").
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
    `Engine.setBridgeOrigins`; it starts as the Chrome extension of `ui/apps/extension` (#546), and Firefox, whose origin is
    random per install, is added by the user; an empty list means any extension, and is stored as `*`), JSON only, 64 KB at
    most. `POST /add` reads the link with `Engine.probeLink` and answers
    with a token valid 15 s; `POST /confirm` redeems it once. A browser's cookies are sealed: the keychain holds the text
    (`CookieVault`, via `openStore(keychain = ...)`) and the row holds a `sealed:` marker, so the database has no cookies; old
    rows are moved at the next start; with no keychain they live in memory for the run, and after a restart the download
    fails as needing a login. `FileSingleInstance` keeps one app per user: a lock file plus a loopback socket with a secret in
    the data folder, and a second launch hands its arguments over and exits. `NotificationRelay` turns finished, failed and
    allowance-reached events into notifications through a `Notifier` (`notifierForCurrentOs`); `Engine.setNotifications`
    switches them. Nothing here registers `magnet:` or `anydm://` with the OS; that is packaging.
  - The clipboard watch and the global hotkey (#545, part 2) are off until their settings are on (`Engine.setClipboardWatch`,
    `Engine.setGlobalHotkey`; the services follow `Engine.osSettings`, so a change needs no restart). `ClipboardWatch` looks at
    the clipboard about every 500 ms and announces a copied link once on `links` (web, FTP, SFTP, magnet and `.torrent`
    addresses on one line of at most 2 KB, no login in it); it adds nothing, and the link that was there when it started is not
    news. `HotkeyService` runs a `GlobalHotkey` (Cmd+Shift+D on macOS, Ctrl+Shift+D elsewhere) and hands each press to
    `HotkeyQueue`, which adds what the clipboard holds as an `EntryPoint.HOTKEY` link and says in one notification what came of
    it. The capture is JNativeHook (LGPL, an unmodified 0.7 MB jar with its natives inside): a `ChordDetector` sees only
    Shift, Ctrl/Cmd and D, so no other key is kept or logged, and the library's own logging is off. `HotkeyService.status` says
    why it is not running: `NeedsPermission` (macOS Accessibility), `Unsupported` (Linux under Wayland or without an X server, or
    no library for the system), `Failed`. The key is only listened to, so a browser still sees Ctrl/Cmd+Shift+D (its "bookmark all
    tabs"). Two things the host has to do: pass `nativeGlobalHotkey(libraryFolder)` a folder of the app's own under the user's
    data folder (the library unpacks its native file next to its own jar otherwise, which in an installed app is not writable and
    would break a signed bundle; never the shared temp folder), and cancel the service, which stops the hook, before the app exits
    (the library's hook thread is not a daemon, so the process would stay alive with a system-wide key hook). Ship the jar as it
    is, not merged into an uber jar: it is LGPL. Tests use fakes: none touches the real clipboard or installs a real key hook, and only that the library loads on
    this JDK is checked against the real system.
- `desktopApp/` — the Compose Desktop app, which drives the engine directly (no server in between). Its look and layout follow
  [Plexo](https://github.com/anmolkapil/plexo) (see Credits below): a home screen with the downloads in cards grouped by state
  (Downloading, Queued, Paused, Needs attention, Finished), a networks pill in the header, a full-page detail view for one
  download, and a footer with free disk space, Slow mode and the speed unit (MB/s or Mbps). The screens:
  - Downloads (`HomeScreen`, `HomeModel`): filter, multi-select with Cmd/Ctrl+A, bulk pause, resume and remove, "Clear finished"
    (keeps the files); paste a link with Cmd/Ctrl+V or drop a `.torrent`.
  - Landing (`LandingScreen`, `NewDownloadForm`, `NewDownloadModel`): the page the window opens on, one link field: web, FTP, magnet, site or `.torrent`; reads the link first (name, size, torrent file tree with
    checkboxes), the folder and which networks to use (each with its own color and a pencil to rename it).
  - Detail (`DetailScreen`, `DetailModel`): throughput graph, per-network table, chunk map for web downloads, peers and pieces for
    torrents, the combine diagram, Open / Show in folder / Move to Trash.
  - Networks (`NetworksMenu`, `NetworkEdit`): switch a network on or off, rename it, pick its color.
  - Limits (`LimitsDialog`, `LimitsModel`): the total speed limit, Slow mode, downloads at once, a download schedule (stored as
    pause rules, so it needs no engine change), and per network a speed limit and a daily, weekly or monthly data limit with
    "Reset data usage".
  - Browsers (`PairBrowserDialog`, `PairBrowserModel`): pairs the browser extension with the app. The dialog shows a one-time
    code (16 characters in groups of four, good for two minutes, with a Copy button that takes the code off the clipboard
    again when the dialog goes); you type it into the extension's popup. The code never crosses the wire, only proofs made
    with it do; a paired browser then signs its requests, which is what lets the extension send cookies for the download.
    The list shows each paired browser with Revoke. The protocol is `BridgePairing` in `shared` (`engine/os`).
  The engine is the same one as before (`openDesktopSource`: HTTP/HTTPS, yt-dlp media, libtorrent, FTP/SFTP, logins in the
  keychain), behind a small `EngineSource` that the tests replace with `FakeSource`. The new window has no Settings screen, tray,
  clipboard watch, global hotkey or notifications: the engine still has those services, this app does not start them. The data
  folder holds `network-visuals.json` (the names and colors given to networks) beside the engine's own data; theme and speed unit
  are kept with `java.util.prefs`. A second launch hands its arguments (links, `.torrent` files) to the running window and raises it.

  Fonts: Manrope and Roboto Mono, bundled under the SIL Open Font License; the license texts are in
  `desktopApp/src/main/resources/fonts/LICENSES.md`.

  Run and test (from `cross/`, or with the `make` targets from the repository root):

  ```bash
  ./gradlew :desktopApp:run                                   # make cross-run
  ./gradlew :desktopApp:run -Panydm.userHome=/tmp/anydm-try   # make cross-run CROSS_HOME=/tmp/anydm-try
  ./gradlew :desktopApp:test                                  # make cross-test (also runs :shared:allTests)
  ```

  `-Panydm.userHome=<folder>` runs the app with that folder as `user.home`, so the engine's data, logs and downloads stay out
  of your own. (The theme and speed unit, kept with `java.util.prefs`, may still land in the real home on macOS, which can
  ignore `user.home`.)

  Packaging: `make cross-package` builds an unsigned dmg / msi / deb of whatever the app is at that point. The installed
  runtime is cut down to the JDK modules listed in `desktopApp/build.gradle.kts` (`suggestRuntimeModules` lists what the class
  path needs), one libtorrent native for the build's OS, and the JVM flags the engine needs (`--add-opens java.base/java.net`
  and `java.io` for binding, `--enable-native-access` for SQLite, JNA and libtorrent, whose natives sit inside their jars and
  are unpacked at run time). `make cross-smoke` proves it: it builds the app image and starts it with `--self-check`, which
  opens the engine on a throwaway home folder, downloads a file from a server on the machine and compares it, and checks that
  torrents loaded; the exit code is the answer. On macOS with a Homebrew JDK, Compose refuses to package unless you add
  `compose.desktop.packaging.checkJdkVendor=false` to your own `~/.gradle/gradle.properties` (or pass it with `-P`).

  What each installer writes:

  | OS | `.torrent` | `magnet:` and `anydm://` |
  | --- | --- | --- |
  | macOS (dmg) | `CFBundleDocumentTypes` in the app's `Info.plist` | `CFBundleURLTypes` in the same file; macOS reads it when the app is in Applications and has been opened once. Links reach the running app as Apple events (`OpenEvents`) |
  | Windows (msi) | File association by the installer | Not registered: the settings screen that wrote the per-user registry keys is gone with the old window |
  | Linux (deb) | `MimeType` in the installed `.desktop` file | Not registered, for the same reason |

  The packaging smoke test is not in CI yet (a matrix job running `make cross-smoke` on each OS is the follow-up, with
  `xvfb-run` on Linux). Installers are unsigned: macOS will ask you to allow the app in Privacy & Security (or
  `xattr -cr anydm.app`), and Windows SmartScreen shows "unknown publisher". There is no auto-update and no telemetry.
  The by-hand checks for the screens, the pairing and the packaged app are in one list in the task notes of the Plexo parity
  work (`docs/conf/tasks/plexo-parity/status.md`, not tracked in git).

  Credits: the design of this window (layout, color tokens, how networks are shown and combined, the Limits dialog) is modeled on [Plexo](https://github.com/anmolkapil/plexo) (MIT licensed, "Copyright (c) 2026 Anmol Kapil"). It is a design reference only:
  no Plexo code was copied, the app is written from scratch on this engine. The bundled fonts, Manrope and Roboto Mono, are
  used under the SIL Open Font License 1.1 (see above).

  Known limits: Firefox cannot pair with the desktop app yet. Firefox gives each install its own `moz-extension://` origin, the
  app answers only the origins in `bridgeOrigins` (the Chrome extension's, by default), and the new window has no settings
  screen: nothing in it can edit that list (`Engine.setBridgeOrigins` exists, but no screen calls it). Follow-up: an app-side
  way to allow a Firefox extension origin.

The app icon is drawn by `desktopApp/icons/make_icons.py`; after changing it, run
`uv run --no-project --with pillow python icons/make_icons.py` from `desktopApp/`.

Needs JDK 25. The Gradle wrapper is committed, so there is nothing else to install.

```bash
make cross-check     # ktlint, tests, compile
make cross-test      # shared and desktop app tests
make cross-run       # open the app (CROSS_HOME=<folder> for a throwaway home)
make cross-package   # dmg / msi / deb for this OS
make cross-smoke     # build the app image and run its self-check
```
