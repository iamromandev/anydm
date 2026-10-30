package dev.anydm.engine.tools

import kotlinx.io.files.Path

/** Looks for a program the way a shell does (BIN-03). */
fun interface PathFinder {
    /** The first file named [executable] (with its `.exe` on Windows) in the folders of `PATH`, or null. */
    fun find(executable: String): Path?
}
