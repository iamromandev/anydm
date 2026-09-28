package dev.anydm.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/** The desktop client's window. Part 3 of the milestone fills it in. */
fun main() =
    application {
        Window(onCloseRequest = ::exitApplication, title = "anydm") {
            MaterialTheme {
                Text("anydm")
            }
        }
    }
