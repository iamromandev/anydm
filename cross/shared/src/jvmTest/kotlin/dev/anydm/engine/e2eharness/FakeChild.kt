package dev.anydm.engine.e2eharness

import kotlin.system.exitProcess

/** A stand-in child for EngineProcessRunner's tests: what it prints and how it ends depends on args[0]. */
object FakeChild {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "count" -> {
                println("progress 10")
                Thread.sleep(200)
                println("progress 20")
                Thread.sleep(200)
                println("done")
            }

            "hang-after-count" -> {
                println("progress 10")
                Thread.sleep(200)
                println("progress 20")
                Thread.sleep(Long.MAX_VALUE)
            }

            "fail" -> {
                System.err.println("boom")
                exitProcess(1)
            }

            "hang" -> {
                Thread.sleep(Long.MAX_VALUE)
            }

            "echo" -> {
                println("progress 1")
                args.drop(1).forEach { println("arg $it") }
                println("done")
            }
        }
        System.out.flush()
    }
}
