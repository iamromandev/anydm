package dev.anydm.engine.os

import com.github.kwhat.jnativehook.GlobalScreen
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The key capture library on this JVM (OSI-06). Its documentation lists Java up to 17, so this shows it loads on the one the
 * app runs on, and that it unpacks its native file into the folder it is told to (the library's default is next to its own
 * jar, which in an installed app is not writable). It never registers the hook: that would ask this machine for the
 * Accessibility permission.
 */
class JNativeHookLoadTest {
    private val folder = Files.createTempDirectory("anydm-jnativehook-test-").toFile()

    @AfterTest
    fun clean() {
        folder.deleteRecursively()
    }

    @Test
    fun theLibraryLoadsOnThisJdkAndUnpacksWhereItIsTold() {
        JNativeHookApi.useLibraryFolder(folder)

        GlobalScreen.isNativeHookRegistered() shouldBe false

        folder.listFiles().orEmpty().any { it.name.contains("JNativeHook", ignoreCase = true) } shouldBe true
    }
}
