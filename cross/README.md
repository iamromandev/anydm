# cross/

The anydm desktop client: Kotlin Multiplatform, with Compose for the window. It talks to an anydm
server over the same API as the web UI, wherever that server runs.

- `shared/` — the API client (`AnydmApi`), the model, the live event stream, and the store the UI
  observes: `TaskStore`, which keeps the list live, and `SettingsStore`. Only the `jvm()`
  target for now; Android and iOS would add targets here.
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

`shared/src/jvmTest/resources/fixtures/` holds JSON recorded from a real API; see its README.
