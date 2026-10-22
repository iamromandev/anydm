package dev.anydm.engine.os

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * [SingleInstance] with a lock file and a loopback socket in [folder] (OSI-08). The first launch takes an exclusive lock on
 * `instance.lock` (the OS frees it if the process dies, so it never goes stale), listens on a free loopback port and writes
 * the port and a random secret to `instance.json`, readable by its owner only from the moment it exists. A later launch reads
 * that file, connects, and sends one line of JSON with the secret and its arguments, a relative path to a `.torrent` made
 * absolute from its own folder; the first answers `ok` at once, before it acts on them, and then takes them in the order
 * they came. A message with a wrong secret, bad form, or more than [MAX_MESSAGE] bytes, or one that takes longer than
 * [readWithin] to arrive, is dropped without an answer. If the first does not answer within [answerWithin] the later launch
 * goes on alone, and so does one that can't use the folder at all: a guard against a second copy never stops the first.
 */
class FileSingleInstance(
    private val folder: File,
    private val answerWithin: Duration = 5.seconds,
    private val readWithin: Duration = 5.seconds,
) : SingleInstance {
    private val lockFile = File(folder, "instance.lock")
    private val infoFile = File(folder, "instance.json")

    private var lockHolder: RandomAccessFile? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null
    private var scope: CoroutineScope? = null

    // Files this process opened and could not lock because it holds the lock itself (another instance in this process): kept
    // open, since closing one can drop every lock the process has on the file.
    private val spare = mutableListOf<RandomAccessFile>()

    override suspend fun start(
        arguments: List<String>,
        onArguments: suspend (List<String>) -> Unit,
    ): SingleInstance.Role =
        withContext(Dispatchers.IO) {
            try {
                folder.mkdirs()
                if (becomePrimary(onArguments)) SingleInstance.Role.PRIMARY else handOver(absolutized(arguments), onArguments)
            } catch (_: IOException) {
                // A folder that can't be locked, listened in or written: no guard, but the app starts.
                close()
                SingleInstance.Role.ALONE
            }
        }

    // Takes the lock and starts listening; false when someone else holds it.
    private fun becomePrimary(onArguments: suspend (List<String>) -> Unit): Boolean {
        val holder = RandomAccessFile(lockFile, "rw")
        val taken =
            try {
                holder.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                spare += holder
                return false
            }
        if (taken == null) {
            holder.close()
            return false
        }
        lockHolder = holder
        lock = taken
        listen(onArguments)
        return true
    }

    private fun listen(onArguments: suspend (List<String>) -> Unit) {
        val secret = SecureRandom().let { random -> ByteArray(16).also(random::nextBytes) }.joinToString("") { "%02x".format(it) }
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = socket
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = work
        // One after the other, as they came.
        val queue = Channel<List<String>>(Channel.UNLIMITED)
        work.launch { for (arguments in queue) runCatching { onArguments(arguments) } }
        publish(socket.localPort, secret)
        Thread({ accept(socket, secret, queue) }, "anydm-instance").apply { isDaemon = true }.start()
    }

    // The file is created private, written whole and moved into place, so a later launch never reads half of it and the
    // secret is never readable by anyone else, not even for a moment.
    private fun publish(
        port: Int,
        secret: String,
    ) {
        val temporary = File(folder, "instance.json.tmp").toPath()
        Files.deleteIfExists(temporary)
        try {
            Files.createFile(
                temporary,
                PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)),
            )
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX file system: the folder is the user's own.
            Files.createFile(temporary)
        }
        Files.writeString(
            temporary,
            buildJsonObject {
                put("port", port)
                put("secret", secret)
            }.toString(),
        )
        Files.move(temporary, infoFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun accept(
        socket: ServerSocket,
        secret: String,
        queue: Channel<List<String>>,
    ) {
        while (!socket.isClosed) {
            val client =
                try {
                    socket.accept()
                } catch (_: IOException) {
                    // Closed, or a passing trouble (out of descriptors): only the first ends it.
                    if (socket.isClosed) return
                    Thread.sleep(100)
                    continue
                }
            try {
                client.soTimeout = readWithin.inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                val arguments = readMessage(client, secret)
                if (arguments != null) {
                    client.getOutputStream().apply {
                        write("ok\n".encodeToByteArray())
                        flush()
                    }
                    queue.trySend(arguments)
                }
            } catch (_: IOException) {
                // A client that stalls or breaks is dropped.
            } finally {
                runCatching { client.close() }
            }
        }
    }

    // The arguments of a well-formed message with the right secret that arrives in time, else null.
    private fun readMessage(
        client: Socket,
        secret: String,
    ): List<String>? {
        val started = TimeSource.Monotonic.markNow()
        val input = client.getInputStream()
        val line = ByteArrayOutputStream()
        while (true) {
            // One byte at a time, however it drips, still has to be all there within the time.
            if (started.elapsedNow() > readWithin) return null
            val byte = input.read()
            if (byte < 0 || byte == '\n'.code) break
            if (line.size() >= MAX_MESSAGE) return null
            line.write(byte)
        }
        val message =
            try {
                Json.parseToJsonElement(line.toByteArray().decodeToString()) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
        val given = (message["secret"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!MessageDigest.isEqual(given.encodeToByteArray(), secret.encodeToByteArray())) return null
        val list = message["arguments"] as? JsonArray ?: return null
        if (list.size > MAX_ARGUMENTS) return null
        return list.map { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content ?: return null }
    }

    // Tries the running instance until it takes the arguments or the wait is over; if it goes away meanwhile, takes its place.
    private suspend fun handOver(
        arguments: List<String>,
        onArguments: suspend (List<String>) -> Unit,
    ): SingleInstance.Role {
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            val remaining = answerWithin - started.elapsedNow()
            if (remaining <= Duration.ZERO) return SingleInstance.Role.ALONE
            val info = readInfo()
            if (info != null) {
                val (port, secret) = info
                val message =
                    buildJsonObject {
                        put("secret", secret)
                        putJsonArray("arguments") { arguments.forEach { add(JsonPrimitive(it)) } }
                    }.toString()
                // Too big to ever be taken: waiting would not help.
                if (message.encodeToByteArray().size >= MAX_MESSAGE) return SingleInstance.Role.ALONE
                if (deliver(port, message, remaining)) return SingleInstance.Role.HANDED_OVER
            }
            delay(RETRY_EVERY)
            if (becomePrimary(onArguments)) return SingleInstance.Role.PRIMARY
        }
    }

    private fun readInfo(): Pair<Int, String>? =
        try {
            val info = Json.parseToJsonElement(infoFile.readText()) as JsonObject
            val port = (info["port"] as JsonPrimitive).content.toInt()
            val secret = (info["secret"] as JsonPrimitive).content
            port to secret
        } catch (_: Exception) {
            // Not there yet (the first is starting), or half-written, or left by a dead process.
            null
        }

    private fun deliver(
        port: Int,
        message: String,
        within: Duration,
    ): Boolean =
        try {
            Socket().use { socket ->
                val millis = within.inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), millis)
                socket.soTimeout = millis
                socket.getOutputStream().apply {
                    write(message.encodeToByteArray())
                    write('\n'.code)
                    flush()
                }
                socket.getInputStream().readNBytes(3).decodeToString() == "ok\n"
            }
        } catch (_: IOException) {
            false
        }

    override fun close() {
        runCatching { server?.close() }
        scope?.cancel()
        if (lock != null) runCatching { infoFile.delete() }
        runCatching { lock?.release() }
        runCatching { lockHolder?.close() }
        spare.forEach { runCatching { it.close() } }
        spare.clear()
        server = null
        scope = null
        lock = null
        lockHolder = null
    }

    private companion object {
        /** The longest message taken, in bytes: a few hundred links at most. */
        const val MAX_MESSAGE = 64 * 1024
        const val MAX_ARGUMENTS = 256
        val RETRY_EVERY = 50.milliseconds
    }
}

/**
 * [arguments] with a relative path to a `.torrent` made absolute from [folder], this process's own: a second launch's
 * working folder is not the running app's, so a path handed over as typed would name another file. Links and flags stay.
 */
fun absolutized(
    arguments: List<String>,
    folder: File = File("").absoluteFile,
): List<String> =
    arguments.map { argument ->
        val file = File(argument)
        val isPath =
            "://" !in argument && !argument.startsWith("magnet:", ignoreCase = true) && argument.endsWith(".torrent", ignoreCase = true)
        if (isPath && !file.isAbsolute && !argument.startsWith("\\\\") &&
            !argument.startsWith("//")
        ) {
            File(folder, argument).path
        } else {
            argument
        }
    }
