package dev.anydm.engine.os

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/** A second launch as a real process (OSI-08): `folder arguments...`. Exits 0 when its arguments reached the running one. */
fun main(args: Array<String>) {
    val instance = FileSingleInstance(File(args[0]), answerWithin = 5.seconds)
    val role = runBlocking { instance.start(args.drop(1)) { } }
    instance.close()
    exitProcess(if (role == SingleInstance.Role.HANDED_OVER) 0 else 2)
}
