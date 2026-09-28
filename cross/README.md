# cross/

The anydm desktop client: Kotlin Multiplatform, with Compose for the window. It talks to an anydm
server over the same API as the web UI, wherever that server runs.

- `shared/` — the API client (`AnydmApi`), the model, the live event stream, and the store the UI
  observes: `TaskStore`, which keeps the list live, and `SettingsStore`. Only the `jvm()`
  target for now; Android and iOS would add targets here.
- `desktopApp/` — the Compose Desktop app: Connect, the live list, Save into Downloads, Play in
  the system's or a chosen player, and a tray with notifications. `make cross-package` builds an
  unsigned dmg / msi / deb. On macOS with a Homebrew JDK, Compose refuses to package unless you add
  `compose.desktop.packaging.checkJdkVendor=false` to your own `~/.gradle/gradle.properties`.

Needs JDK 21. The Gradle wrapper is committed, so there is nothing else to install.

```bash
make cross-check     # ktlint, tests, compile
make cross-run       # open the app
make cross-package   # dmg / msi / deb for this OS
```

`shared/src/jvmTest/resources/fixtures/` holds JSON recorded from a real API; see its README.
