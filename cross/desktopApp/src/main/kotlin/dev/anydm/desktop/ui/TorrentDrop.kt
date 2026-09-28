package dev.anydm.desktop.ui

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import java.io.File
import java.net.URI

/** A `.torrent` dropped anywhere on this area is added, like one picked with the button. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.torrentDrop(onFile: (File) -> Unit): Modifier =
    composed {
        val target =
            remember(onFile) {
                object : DragAndDropTarget {
                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        val files = event.dragData() as? DragData.FilesList ?: return false
                        val uri = files.readFiles().firstOrNull { it.endsWith(".torrent") } ?: return false
                        onFile(File(URI(uri)))
                        return true
                    }
                }
            }
        dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
    }
