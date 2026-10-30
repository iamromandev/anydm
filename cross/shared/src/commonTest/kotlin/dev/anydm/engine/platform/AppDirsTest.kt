package dev.anydm.engine.platform

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test

class AppDirsTest {
    @Test
    fun foldersMustBeAbsolute() {
        val base = Path(SystemTemporaryDirectory, "anydm")
        val dirs = AppDirs(base, Path(base, "logs"), Path(base, "cache"))
        dirs.data shouldBe base
        shouldThrow<IllegalArgumentException> { AppDirs(Path("anydm"), base, base) }
    }
}
