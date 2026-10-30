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
- `desktopApp/` — the Compose Desktop app, built in [#604](https://github.com/iamromandev/anydm/issues/604); it drives
  the engine directly. In place so far: the shell (window, theme, sidebar with live counts, status bar, menu bar,
  banners, a first-run welcome) and the download list (#606): sorting, a text filter, expandable collections,
  multi-selection with keys, a context menu and toolbar for every job command, the remove and relink dialogs, a detail
  pane for one selected job, and open / show in folder / copy path for finished files. `AppModel` holds the state every
  screen reads and takes the commands; the list logic (`Rows.kt`, `Commands.kt`, `Format.kt`) is plain Kotlin with
  its own tests. Adding downloads (#607): `openDesktopSource` wires every protocol (HTTP/HTTPS, media pages and playlists
  through yt-dlp, which the tool manager fetches into the cache folder, torrents through libtorrent unpacked into the data
  folder, FTP/FTPS/SFTP) with logins in the system keychain; a part that can't be set up is left out and its links show as
  unavailable. The add dialog (`AddModel`, `AddForm.kt`) reads each pasted link before it is added (batch patterns such as
  `[1-10]` expand), and takes the category, profile, folder, start, media format and login for all of them;
  `AppModel.openAdd(links)` opens it with links in it. Transfer and file settings (#608): the Settings screen
  (`SettingsModel`, `SettingsForm.kt`) edits the default folder, clash policy, disk reserve, retries, history, pacing,
  connections, speed limits, slow mode, seeding, the queue and what happens after it, through
  `Engine.applyTransferSettings` so each applies without a restart; it adds, edits and orders categories and profiles,
  shows the history and 30 days of traffic, resets to defaults, and exports or imports the lot as JSON
  (`SettingsBackup.kt`: no proxies, cookies or logins). Network, OS and tool settings (#609): six more sections of the
  Settings screen, each with its own presenter in `SettingsPanels` (`ProxyModel`, `RulesModel`, `NetworksModel`,
  `OsModel`, `ToolsModel`, `DiagnosticsModel`). They have no Save: each change goes to the engine at once, which applies it
  to running downloads. Proxies are saved with their password in the keychain, chosen globally, per profile (the profile
  editor) and per download (the detail pane's Proxy tab). Network rules edit the default and per-network limits, data
  allowances, metered handling and the weekly schedule. Networks switch bonding on per network and show whether the OS can
  bind to it (`BEST_EFFORT` on Linux). The system section holds notifications, clipboard watch, the global hotkey and its
  status, and the browser bridge origins. Tools shows yt-dlp, ffmpeg and Deno, fetches the newest (`Engine.updateTool`),
  and takes a folder to use instead (`Settings.toolFolders`). Diagnostics switches debug logging (live) and exports the
  bundle (`DiagnosticsBundle`); the desktop engine now writes `anydm.log` to the logs folder. OS integration (#610): `main(args)`
  asks `FileSingleInstance` first, so a second launch hands its arguments (`LinkRouter`: magnet, `.torrent`, web, FTP, SFTP,
  `anydm://add`; the rest is dropped) to the running window, which opens them in the add review. The tray (`TrayMenu`:
  show or hide, open, pause or resume all, quit) and the window choices (`WindowModel`: close to the tray, start hidden or
  with `--hidden`; both only where a tray exists) live in `AppPrefs`. `OsServices` runs `NotificationRelay`,
  `ClipboardWatch` (a "link copied" offer), the global `HotkeyService` (opens the add review with the clipboard) and the
  `BridgeServer` beside the engine, each following its setting; `OsSignals` carries their state to the status bar
  (keep-awake, after-queue countdown, bridge port, hotkey) and the permission notice (macOS Accessibility, Linux Wayland).
  A notification click can't reach the app through osascript or notify-send, so clicking the tray icon opens the row of the
  last finished or failed download.
  `make cross-package` builds an unsigned dmg / msi / deb of whatever the app is at that point. On
  macOS with a Homebrew JDK, Compose refuses to package unless you add
  `compose.desktop.packaging.checkJdkVendor=false` to your own `~/.gradle/gradle.properties`.

The app icon is drawn by `desktopApp/icons/make_icons.py`; after changing it, run
`uv run --no-project --with pillow python icons/make_icons.py` from `desktopApp/`.

Needs JDK 25. The Gradle wrapper is committed, so there is nothing else to install.

```bash
make cross-check     # ktlint, tests, compile
make cross-run       # open the app
make cross-package   # dmg / msi / deb for this OS
```
