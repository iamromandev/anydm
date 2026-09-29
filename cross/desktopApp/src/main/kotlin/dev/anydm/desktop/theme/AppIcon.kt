package dev.anydm.desktop.theme

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import javax.imageio.ImageIO

/** The app icon (256 px, drawn by icons/make_icons.py), for the window and the Connect screen. */
val AppIcon: Painter by lazy {
    val stream = checkNotNull(object {}.javaClass.getResourceAsStream("/icon.png")) { "icon.png is missing from resources" }
    BitmapPainter(stream.use { ImageIO.read(it) }.toComposeImageBitmap())
}
