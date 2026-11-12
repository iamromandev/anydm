plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinter)
}

kotlin {
    jvmToolchain(25)
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
