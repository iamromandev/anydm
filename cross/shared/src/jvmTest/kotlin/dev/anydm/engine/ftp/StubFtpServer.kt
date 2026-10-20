package dev.anydm.engine.ftp

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A scripted FTP server for what a real one won't do: refuse `REST`, know no `SIZE` or `MDTM`, or hang up. It serves
 * [content] for every `RETR`, accepts any login, and speaks only the commands a passive download sends.
 */
internal class StubFtpServer(
    private val content: ByteArray = ByteArray(0),
    private val restReply: String = "350 Restarting",
    private val sizeReply: String? = null,
    private val hangUpAfterGreeting: Boolean = false,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    @Volatile private var closed = false
    val commands = mutableListOf<String>()

    init {
        thread(isDaemon = true, name = "stub-ftp") {
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(socket) } }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            val input = it.getInputStream().bufferedReader(Charsets.UTF_8)
            val output = it.getOutputStream().bufferedWriter(Charsets.UTF_8)

            fun reply(line: String) {
                output.write("$line\r\n")
                output.flush()
            }
            reply("220 stub ready")
            if (hangUpAfterGreeting) return
            var data: ServerSocket? = null
            var restart = 0
            while (true) {
                val line = input.readLine() ?: return
                synchronized(commands) { commands += line }
                val verb = line.substringBefore(' ').uppercase()
                when (verb) {
                    "USER" -> {
                        reply("331 need password")
                    }

                    "PASS" -> {
                        reply("230 welcome")
                    }

                    "TYPE" -> {
                        reply("200 type set")
                    }

                    "PASV" -> {
                        data = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                        val p = data.localPort
                        reply("227 Entering Passive Mode (127,0,0,1,${p / 256},${p % 256})")
                    }

                    "REST" -> {
                        reply(restReply)
                        if (restReply.startsWith("350")) restart = line.substringAfter(' ').trim().toInt()
                    }

                    "SIZE" -> {
                        reply(sizeReply ?: "213 ${content.size}")
                    }

                    "MDTM" -> {
                        reply(if (sizeReply == null) "213 20240102030405" else "502 not implemented")
                    }

                    "RETR" -> {
                        val socketForData = data?.accept()
                        reply("150 opening data connection")
                        socketForData?.use { s -> s.getOutputStream().write(content, restart, content.size - restart) }
                        data?.close()
                        reply("226 done")
                    }

                    "QUIT" -> {
                        reply("221 bye")
                        return
                    }

                    else -> {
                        reply("502 not implemented")
                    }
                }
            }
        }
    }

    override fun close() {
        closed = true
        server.close()
    }
}
