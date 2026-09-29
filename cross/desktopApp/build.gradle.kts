import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
