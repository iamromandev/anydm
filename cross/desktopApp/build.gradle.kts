import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.time.Duration
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlinter)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.compose.ui.test)
}

// BIN-05: the installer carries libtorrent's native for the OS it is built on and nothing else.
val nativeOs = rootProject.extra["libtorrentNativeOs"] as String

// libtorrent4j's Linux jar also carries an ARM build that TorrentNatives never loads (14 MiB uncompressed): the app gets the
// jar for its OS without it, repackaged here.
// Measured (2026-10-21): the three native jars were 5.1 MB (macOS), 12.8 MB (Linux) and 4.8 MB (Windows) and all rode in every
// installer; now one does, the Linux one at 6.6 MB. The macOS .dmg went from 116,338,376 to 98,798,681 bytes. The Linux and
// Windows installers can't be built on a Mac: their savings are the jar sizes.
val trimLibtorrentNative =
    tasks.register<Jar>("trimLibtorrentNative") {
        description = "Repackages libtorrent4j's native for $nativeOs without the CPU builds the app never loads."
        archiveFileName.set("libtorrent4j-native-$nativeOs-trimmed.jar")
        destinationDirectory.set(layout.buildDirectory.dir("libtorrent"))
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        val os = nativeOs
        val original =
            configurations.detachedConfiguration(dependencies.create("org.libtorrent4j:libtorrent4j-$os:${libs.versions.libtorrent4j.get()}")).apply {
                isTransitive = false
            }
        from({ original.files.map { zipTree(it) } }) { exclude("lib/arm/**") }
    }

dependencies {
    runtimeOnly(files(trimLibtorrentNative))
}

// Fails unless the app's class path holds exactly one libtorrent native, the one for [nativeOs], whole but for the libraries
// of other CPUs that TorrentNatives never loads. Run it for the others with -Panydm.nativeOs=macos|linux|windows.
val checkDesktopNatives =
    tasks.register("checkDesktopNatives") {
        description = "Checks that the app carries one libtorrent native, its own OS's, without the unused CPU builds."
        group = "verification"
        val runtime = configurations.named("runtimeClasspath")
        inputs.files(runtime)
        val os = nativeOs
        val wanted =
            mapOf(
                "macos" to "lib/arm64/libtorrent4j.dylib",
                "linux" to "lib/x86_64/libtorrent4j.so",
                "windows" to "lib/x86_64/libtorrent4j.dll",
            ).getValue(os)
        doLast {
            val nativeJar = Regex("""libtorrent4j-(macos|linux|windows|native-(macos|linux|windows)-trimmed)(-.*)?\.jar""")
            val natives = runtime.get().files.filter { nativeJar.matches(it.name) }
            check(natives.size == 1) { "expected one libtorrent native on the class path for $os, found: ${natives.map { it.name }}" }
            val jar = natives.single()
            check(jar.name == "libtorrent4j-native-$os-trimmed.jar") { "the class path has ${jar.name}, not the trimmed native for $os" }
            val entries = ZipFile(jar).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
            check(wanted in entries) { "${jar.name} has no $wanted: $entries" }
            check(entries.none { it.startsWith("lib/arm/") }) { "${jar.name} still has the unused arm build: ${entries.filter { it.startsWith("lib/arm/") }}" }
        }
    }
tasks.named("check") { dependsOn(checkDesktopNatives) }

// Starts the installed app image with --self-check: its own engine on a throwaway home folder adds a download from a local
// server and checks the file (SelfCheck). Fails if the runtime lacks a module or a native library. Not part of `check`: it
// builds the app image first.
val smokePackagedApp =
    tasks.register<Exec>("smokePackagedApp") {
        description = "Runs the packaged app's self-check: engine start, a local HTTP download, the torrent native."
        group = "verification"
        dependsOn("createDistributable")
        val home = layout.buildDirectory.dir("smoke-home").get().asFile
        val image = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
        val launcher =
            when {
                org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "anydm.app/Contents/MacOS/anydm"
                org.gradle.internal.os.OperatingSystem.current().isWindows -> "anydm/anydm.exe"
                else -> "anydm/bin/anydm"
            }
        doFirst { home.deleteRecursively() }
        // The self-check keeps its data and downloads under the folder it is given, and user.home points at the same place, so
        // nothing of the user's is touched.
        environment("JAVA_TOOL_OPTIONS", "\"-Duser.home=$home\"")
        commandLine(File(image, launcher).path, "--self-check", File(home, "check").path)
        timeout.set(Duration.ofMinutes(3))
    }

// The Info.plist entry that makes macOS hand `scheme:` links to the app (as an Apple event, see OpenEvents.kt).
fun urlSchemes(vararg schemes: String): String =
    """
    <key>CFBundleURLTypes</key>
    <array>${schemes.joinToString("") { name ->
        """
        <dict>
            <key>CFBundleURLName</key><string>$name link</string>
            <key>CFBundleURLSchemes</key><array><string>$name</string></array>
        </dict>"""
    }}
    </array>
    """.trimIndent()

compose.desktop {
    application {
        mainClass = "dev.anydm.desktop.MainKt"
        // Binding a connection to one network (#544) reads the socket's native handle by reflection.
        jvmArgs +=
            listOf(
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                // SQLite, libtorrent, JNA and JNativeHook load native libraries; newer JDKs warn, then refuse, without this.
                "--enable-native-access=ALL-UNNAMED",
            )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // The runtime is cut down to the modules asked for here. The browser bridge (OSI-01) is the JDK's own HTTP server;
            // SQLite goes through java.sql; the window choices use java.prefs; JNA and libtorrent4j want jdk.unsupported;
            // OkHttp checks host names with javax.naming; sshj and the keychain libraries use GSS and java.management.
            // `suggestRuntimeModules` lists what the class path needs; `smokePackagedApp` proves the result starts.
            modules(
                "java.instrument",
                "java.logging",
                "java.management",
                "java.naming",
                "java.prefs",
                "java.security.jgss",
                "java.sql",
                "jdk.httpserver",
                "jdk.security.auth",
                "jdk.unsupported",
            )
            packageName = "anydm"
            packageVersion = "1.0.0"
            description = "anydm desktop client"
            vendor = "anydm"
            // Opening a .torrent with the app: the installer writes the association (macOS Info.plist, Windows registry,
            // Linux .desktop file). magnet: and anydm:// are registered below on macOS and by the app itself elsewhere.
            fileAssociation("application/x-bittorrent", "torrent", "BitTorrent file")
            macOS {
                bundleID = "dev.anydm.desktop"
                iconFile.set(project.file("icons/anydm.icns"))
                infoPlist {
                    extraKeysRawXml = urlSchemes("magnet", "anydm")
                }
            }
            windows {
                iconFile.set(project.file("icons/anydm.ico"))
            }
            linux {
                iconFile.set(project.file("icons/anydm.png"))
            }
        }
    }
}
