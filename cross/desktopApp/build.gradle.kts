import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlinter)
}

kotlin {
    jvmToolchain(21)
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

compose.desktop {
    application {
        mainClass = "dev.anydm.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "anydm"
            packageVersion = "1.0.0"
            description = "anydm desktop client"
            vendor = "anydm"
            macOS {
                bundleID = "dev.anydm.desktop"
                iconFile.set(project.file("icons/anydm.icns"))
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
