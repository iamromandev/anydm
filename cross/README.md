# cross/

The anydm desktop client: Kotlin Multiplatform, with Compose for the window. It downloads on its
own, with the engine in `shared/`; no server is needed.

- `shared/` — the download engine, being built in #548–#556, in three packages:
  `dev.anydm.engine.core` (job model, states, queue), `dev.anydm.engine.store` (SQLite through
  SQLDelight) and `dev.anydm.engine.platform` (files, app folders, clock). Only the `jvm()` target
  for now; Android and iOS would add targets here. Source sets:
  - `commonMain` is pure Kotlin.
  - `jvmCommonMain` holds code that needs JVM libraries. `jvm` depends on it now and Android will
    later, so nothing desktop-only (`java.awt`, Swing) goes there. Until an Android target exists
    to enforce that, the `checkJvmCommonApis` task (part of `check`) fails on such references.
  - `jvmMain` is desktop only.
  - Each has a matching `*Test` source set.
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
