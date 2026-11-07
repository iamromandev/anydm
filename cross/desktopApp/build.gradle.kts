import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
    testImplementation(kotlin("test"))
}

// The one place the app's version is written; release.yml checks the tag against it.
val appVersion = "1.0.0"

compose.desktop {
    application {
        mainClass = "dev.anydm.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "anydm"
            packageVersion = appVersion
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
