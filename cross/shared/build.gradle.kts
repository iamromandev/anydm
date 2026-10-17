plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.sqldelight)
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
                dependencies {
                    // The HTTP transport (#567).
                    implementation(libs.okhttp)
                }
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
            // The SQLDelight plugin adds its runtime here as `api`: Store takes a SqlDriver.
            // Public: the platform interfaces take kotlinx.io.files.Path.
            api(libs.kotlinx.io.core)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            // SQLite over JDBC for desktop. Not jvmCommon: Android brings its own driver.
            implementation(libs.sqldelight.sqlite.driver)
        }
        jvmTest.dependencies {
            // FaultServer: a local server with ranges and injected faults (#567).
            implementation(libs.okhttp.mockwebserver)
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

val schemaSnapshots = layout.projectDirectory.dir("src/commonMain/sqldelight/databases")

sqldelight {
    databases {
        create("AnydmDb") {
            packageName = "dev.anydm.engine.store.db"
            // One snapshot per schema version (1.db, 2.db, ...) from generateCommonMainAnydmDbSchema, committed and never
            // edited. verifyCommonMainAnydmDbMigration (part of check) migrates each to the latest with the .sqm files and
            // fails if that isn't the .sq schema, or if there is no snapshot.
            schemaOutputDirectory = schemaSnapshots
            verifyMigrations = true
        }
    }
}

// SQLDelight adds its generated Kotlin to commonMain; ktlint checks only the code we write.
val generatedSources = layout.buildDirectory.dir("generated").get().asFile
tasks.withType<org.jmailen.gradle.kotlinter.tasks.ConfigurableKtLintTask>().configureEach {
    // A local, so the lambda doesn't capture the script (the configuration cache can't store that).
    val generated = generatedSources
    exclude { it.file.startsWith(generated) }
}

// The plugin doesn't treat the snapshots as inputs of its verify task, so deleting or editing one would leave the task
// up to date (or cached) and the check would pass without them.
tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    inputs.files(fileTree(schemaSnapshots)).withPropertyName("schemaSnapshots").withPathSensitivity(PathSensitivity.RELATIVE)
    // Generating a snapshot and verifying in one build: generate first.
    mustRunAfter(tasks.withType<app.cash.sqldelight.gradle.GenerateSchemaTask>())
}

// The upgrade test opens the committed schema snapshots.
tasks.named<Test>("jvmTest") {
    inputs.dir(schemaSnapshots).withPropertyName("schemaSnapshots").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("anydm.schemaDir", schemaSnapshots.asFile.absolutePath)
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

// With only a JVM target, the compiler also accepts JVM APIs in common code. This is a heuristic
// until a non-JVM target exists (a js or linux target would make the compiler enforce it).
val checkCommonApis =
    tasks.register("checkCommonApis") {
        val sources = fileTree("src") { include("commonMain/**/*.kt", "commonTest/**/*.kt") }
        inputs.files(sources)
        doLast {
            val comments = Regex("""/\*.*?\*/|//[^\n]*""", RegexOption.DOT_MATCHES_ALL)
            // java./javax. anywhere, plus java.lang names that need no import.
            val jvmApi = Regex("""\bjavax?\.[a-z]|\b(System|Thread|Runtime|StrictMath|ProcessBuilder|Math)\s*\.""")
            val offenders = sources.files.filter { jvmApi.containsMatchIn(it.readText().replace(comments, "")) }
            check(offenders.isEmpty()) {
                "JVM APIs in common source sets:\n" + offenders.joinToString("\n") { "  $it" }
            }
        }
    }
tasks.named("check") { dependsOn(checkJvmCommonApis, checkCommonApis) }
