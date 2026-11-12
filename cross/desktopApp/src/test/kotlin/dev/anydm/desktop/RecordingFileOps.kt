package dev.anydm.desktop

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path

/** Wraps real disk operations (in a temp folder) and records them; [failOn] makes the named operation throw. */
class RecordingFileOps(
    private val real: FileOps = RealFileOps,
    var failOn: String? = null,
    /** With [failOn] = "writeTemp": write this many bytes of the data before failing, like a disk that filled up. */
    var partialBytes: Int? = null,
    /** Makes [moveAtomic] throw [AtomicMoveNotSupportedException], like a file system with no atomic rename. */
    var atomicUnsupported: Boolean = false,
) : FileOps {
    val log = mutableListOf<String>()
    val moves = mutableListOf<Pair<Path, Path>>()
    val temps = mutableListOf<Path>()

    private fun step(name: String) {
        log += name
        if (failOn == name) throw IOException("injected failure at $name")
    }

    override fun read(
        path: Path,
        maxBytes: Int,
    ): ByteArray? {
        step("read")
        return real.read(path, maxBytes)
    }

    private var prefix = "settings"

    override fun writeTemp(
        dir: Path,
        bytes: ByteArray,
        prefix: String,
    ): Path {
        this.prefix = prefix
        return writeTemp(dir, bytes)
    }

    override fun writeTemp(
        dir: Path,
        bytes: ByteArray,
    ): Path {
        val partial = partialBytes
        if (failOn == "writeTemp" && partial != null) {
            log += "writeTemp"
            temps.add(real.writeTemp(dir, bytes.copyOf(partial), prefix))
            throw IOException("injected failure at writeTemp")
        }
        step("writeTemp")
        return real.writeTemp(dir, bytes, prefix).also { temps.add(it) }
    }

    override fun moveAtomic(
        from: Path,
        to: Path,
    ) {
        step("moveAtomic")
        moves += from to to
        if (atomicUnsupported) throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "injected: no atomic move")
        real.moveAtomic(from, to)
    }

    override fun moveReplace(
        from: Path,
        to: Path,
    ) {
        step("moveReplace")
        real.moveReplace(from, to)
    }

    override fun ensureDir(path: Path) {
        step("ensureDir")
        real.ensureDir(path)
    }

    override fun delete(path: Path) {
        step("delete")
        real.delete(path)
    }

    override fun listFiles(dir: Path): List<Path> {
        step("listFiles")
        return real.listFiles(dir)
    }
}
