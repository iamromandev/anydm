plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinter)
}

kotlin {
    jvmToolchain(21)
    jvm()

    // Applied explicitly so the jvmCommon edges below extend the default hierarchy instead of
    // disabling it.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // JVM-library code shared by desktop and, later, Android: nothing desktop-only (java.awt,
        // Swing).
        val jvmCommonMain =
            create("jvmCommonMain") {
                dependsOn(commonMain.get())
            }
        val jvmCommonTest =
            create("jvmCommonTest") {
                dependsOn(commonTest.get())
                dependencies {
                    // Kotest brings an older kotlin-reflect; keep it on the compiler's version.
                    implementation(kotlin("reflect"))
                }
            }
        jvmMain.get().dependsOn(jvmCommonMain)
        jvmTest.get().dependsOn(jvmCommonTest)

        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotest.property)
        }
    }
}

// Android isn't a target yet, so the compiler can't keep desktop-only APIs out of jvmCommon.
val checkJvmCommonApis =
    tasks.register("checkJvmCommonApis") {
        val sources = fileTree("src") { include("jvmCommonMain/**/*.kt", "jvmCommonTest/**/*.kt") }
        inputs.files(sources)
        doLast {
            val desktopOnly = Regex("""\b(java\.awt|javax\.(swing|sound|imageio)|java\.applet)\.""")
            val offenders = sources.files.filter { desktopOnly.containsMatchIn(it.readText()) }
            check(offenders.isEmpty()) {
                "Desktop-only JDK packages in jvmCommon source sets:\n" +
                    offenders.joinToString("\n") { "  $it" }
            }
        }
    }
tasks.named("check") { dependsOn(checkJvmCommonApis) }
