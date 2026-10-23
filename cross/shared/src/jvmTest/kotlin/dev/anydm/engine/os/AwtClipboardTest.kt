package dev.anydm.engine.os

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Reading text off an AWT clipboard (OSI-05). It is always a private clipboard of the test: the system's is never touched. */
class AwtClipboardTest {
    private val flavorOfAnImage = DataFlavor("image/png;class=java.io.InputStream", "an image")

    @Test
    fun theTextOnTheClipboardIsRead() =
        runTest {
            val clipboard = Clipboard("test").also { it.setContents(StringSelection("https://a.example/x.zip"), null) }

            AwtClipboard(clipboard).text() shouldBe "https://a.example/x.zip"
        }

    @Test
    fun anEmptyClipboardHasNoText() =
        runTest {
            AwtClipboard(Clipboard("test")).text() shouldBe null
        }

    @Test
    fun somethingThatIsNotTextIsNoText() =
        runTest {
            val image =
                object : Transferable {
                    override fun getTransferDataFlavors() = arrayOf(flavorOfAnImage)

                    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == flavorOfAnImage

                    override fun getTransferData(flavor: DataFlavor): Any = throw UnsupportedFlavorException(flavor)
                }
            val clipboard = Clipboard("test").also { it.setContents(image, null) }

            AwtClipboard(clipboard).text() shouldBe null
        }

    @Test
    fun aClipboardAnotherProgramHoldsCannotBeReadAndSaysSoInsteadOfBeingEmpty() =
        runTest {
            val busy =
                object : Clipboard("busy") {
                    override fun isDataFlavorAvailable(flavor: DataFlavor) = true

                    override fun getData(flavor: DataFlavor): Any = throw IllegalStateException("cannot open system clipboard")
                }

            assertFailsWith<IllegalStateException> { AwtClipboard(busy).text() }
        }
}
