package dev.anydm.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.anydm.engine.APP_NAME
import java.awt.Dimension

fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = APP_NAME,
            icon = painterResource("icon.png"),
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
        ) {
            window.minimumSize = Dimension(900, 600)
            val sample = if (System.getProperty("anydm.sample") == "empty") emptySample() else detailSample()
            HomeScreen(sample, isSystemInDarkTheme())
        }
    }
