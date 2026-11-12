import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlinter)
}

kotlin {
    jvmToolchain(25)
    // The keychain spike lives in its own source set, so it is compiled by `check` but never shipped in the app.
    target.compilations.create("spike") {
        associateWith(target.compilations.getByName("main"))
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.java.keyring)
    implementation(libs.kotlinx.serialization.json)
    "spikeImplementation"(libs.java.keyring)
    // Not dependencies of ours: these pin what java-keyring brings, to the versions in libs.versions.toml.
    constraints {
        for (pinned in listOf(
            libs.jna,
            libs.jna.platform,
            libs.jkeychain,
            libs.secret.service,
            libs.dbus.java.core,
            libs.dbus.java.transport,
            libs.slf4j.api,
        )) {
            implementation(pinned) { because("java-keyring's transitive libraries are pinned, see libs.versions.toml") }
            "spikeImplementation"(pinned)
        }
    }
    testImplementation(kotlin("test"))
    testImplementation(libs.compose.ui.test)
}

// The one place the app's version is written; release.yml checks the tag against it.
val appVersion = "1.0.0"

compose.desktop {
    application {
        mainClass = "dev.anydm.desktop.MainKt"
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "anydm"
            packageVersion = appVersion
            // What `suggestRuntimeModules` lists for the libraries (JNA, dbus-java) that java-keyring brings; the packaged app's
            // trimmed runtime would otherwise lack them, and only the packaged app would show it.
            modules("java.instrument", "java.net.http", "jdk.security.auth", "jdk.unsupported")
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

tasks.check { dependsOn("spikeClasses") }

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Manual: writes to the real OS keychain, so it is not part of `check`.
tasks.register<JavaExec>("keychainSpike") {
    group = "verification"
    description = "Round-trips a throwaway secret through the OS keychain (set, get, delete)."
    // The app's own runtime classpath, so the spike runs against exactly the libraries the app ships with.
    classpath = sourceSets["spike"].output + sourceSets["main"].runtimeClasspath
    mainClass = "dev.anydm.desktop.spike.KeychainSpikeKt"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
