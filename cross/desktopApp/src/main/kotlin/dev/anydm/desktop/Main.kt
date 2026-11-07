package dev.anydm.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.anydm.engine.APP_NAME

fun main() =
    application {
        Window(onCloseRequest = ::exitApplication, title = APP_NAME, icon = painterResource("icon.png")) {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {}
            }
        }
    }
