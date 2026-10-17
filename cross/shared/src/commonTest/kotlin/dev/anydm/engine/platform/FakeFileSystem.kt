package dev.anydm.engine.platform

import kotlinx.io.files.Path

/**
 * In-memory FileSystem for engine tests. [faults] make an operation fail for a path; [closeFault] makes closing a
 * writer throw; [journal] records every call but writes and syncs. [openWriters] counts the writers not yet closed, so a test can
 * prove nothing leaks. Single-threaded (runTest).
 *
 * It knows what is durable: a writer's sync keeps the file's bytes as they are then, and [powerCut] puts every file back
 * to what was last kept. Making, moving and deleting files is durable at once, a simplification: a real rename survives
 * a power cut only once its folder is synced.
 */
internal class FakeFileSystem(
    var free: Long = Long.MAX_VALUE,
    private val journal: MutableList<String>? = null,
) : FileSystem {
    enum class Op {
        PREPARE_FOLDER,
        PREALLOCATE,
        FREE_SPACE,
        ATOMIC_MOVE,
        MOVE_TO_TRASH,
        OPEN_WRITER,
        DELETE,
        WRITE,
        SIZE,
        SYNC,
        SIZE_OF,
        READ,
    }

    val faults = mutableMapOf<Op, (Path) -> FileResult.Failed?>()

    /** Throws this from `close()` of the writer for a path (the writer still counts as closed). */
    var closeFault: (Path) -> Throwable? = { null }
    var openWriters = 0
        private set
    var syncs = 0
        private set
    private val folders = mutableSetOf<Path>()
    private val files = mutableMapOf<Path, ByteArray>()

    // The bytes each file had at its last sync, or when it was made: what a power cut leaves.
    private val durable = mutableMapOf<Path, ByteArray>()

    /** False: [moveToTrash] says there is no trash. True: it takes the path away and records it in [trashed]. */
    var trashAvailable = false
    val trashed = mutableListOf<Path>()

    fun read(path: Path): ByteArray? = files[path]?.copyOf()

    fun exists(path: Path): Boolean = path in files || path in folders

    fun remove(path: Path) {
        files.remove(path)
        durable.remove(path)
    }

    /**
     * The power goes off: every file loses what was written to it since it was last synced, and every writer open until
     * now is dead (its writes and syncs fail), as nothing of the stopped run reaches the disk after the cut.
     */
    fun powerCut() {
        for (path in files.keys) files[path] = durable.getValue(path).copyOf()
        power++
    }

    // Bumped by each power cut; a writer opened before it is dead.
    private var power = 0

    /** Every file, not folders. */
    fun paths(): Set<Path> = files.keys.toSet()

    /** A file as if the user had put it there; its folders are made too. */
    fun put(
        path: Path,
        bytes: ByteArray,
    ) {
        generateSequence(checkNotNull(path.parent)) { it.parent }.forEach { folders += it }
        files[path] = bytes.copyOf()
        durable[path] = bytes.copyOf()
    }

    override suspend fun prepareFolder(folder: Path): FileResult<Unit> {
        log("prepareFolder", folder)
        fault(Op.PREPARE_FOLDER, folder)?.let { return it }
        if (folder in files) return FileResult.AlreadyExists(folder, "not a folder")
        generateSequence(folder) { it.parent }.forEach { folders += it }
        return FileResult.Ok(Unit)
    }

    override suspend fun preallocate(
        path: Path,
        size: Long,
    ): FileResult<Unit> {
        require(size in 0..MAX_FILE) { "fake files hold up to $MAX_FILE bytes: $size" }
        log("preallocate", path)
        fault(Op.PREALLOCATE, path)?.let { return it }
        if (exists(path)) return FileResult.AlreadyExists(path)
        if (path.parent !in folders) return FileResult.NotFound(path)
        files[path] = ByteArray(size.toInt())
        durable[path] = ByteArray(size.toInt())
        return FileResult.Ok(Unit)
    }

    override suspend fun freeSpace(path: Path): FileResult<Long> {
        log("freeSpace", path)
        return fault(Op.FREE_SPACE, path) ?: FileResult.Ok(free)
    }

    override suspend fun atomicMove(
        source: Path,
        target: Path,
        replace: Boolean,
    ): FileResult<Unit> {
        log("atomicMove", source)
        fault(Op.ATOMIC_MOVE, source)?.let { return it }
        val bytes = files[source] ?: return FileResult.NotFound(source)
        val folder = checkNotNull(target.parent)
        if (folder !in folders) return FileResult.NotFound(folder)
        if (target in folders) return FileResult.AlreadyExists(target, "a folder is in the way")
        if (!replace && target in files) return FileResult.AlreadyExists(target, "a file is already there")
        files.remove(source)
        files[target] = bytes
        durable[target] = durable.remove(source) ?: bytes.copyOf()
        return FileResult.Ok(Unit)
    }

    override suspend fun moveToTrash(path: Path): FileResult<Unit> {
        log("moveToTrash", path)
        fault(Op.MOVE_TO_TRASH, path)?.let { return it }
        if (!trashAvailable) return FileResult.TrashUnavailable(path)
        if (files.remove(path) == null && !folders.remove(path)) return FileResult.NotFound(path)
        durable.remove(path)
        trashed += path
        return FileResult.Ok(Unit)
    }

    override suspend fun openWriter(path: Path): FileResult<FileWriter> {
        log("openWriter", path)
        fault(Op.OPEN_WRITER, path)?.let { return it }
        if (path in folders) return FileResult.AlreadyExists(path, "a folder is in the way")
        if (path !in files) return FileResult.NotFound(path)
        openWriters++
        return FileResult.Ok(Writer(path))
    }

    override suspend fun delete(path: Path): FileResult<Unit> {
        log("delete", path)
        fault(Op.DELETE, path)?.let { return it }
        if (path in folders) return FileResult.AlreadyExists(path, "a folder is in the way")
        durable.remove(path)
        return if (files.remove(path) != null) FileResult.Ok(Unit) else FileResult.NotFound(path)
    }

    override suspend fun size(path: Path): FileResult<Long> {
        log("size", path)
        fault(Op.SIZE_OF, path)?.let { return it }
        if (path in folders) return FileResult.AlreadyExists(path, "a folder is in the way")
        val bytes = files[path] ?: return FileResult.NotFound(path)
        return FileResult.Ok(bytes.size.toLong())
    }

    override suspend fun read(
        path: Path,
        offset: Long,
        length: Int,
    ): FileResult<ByteArray> {
        require(offset >= 0 && length >= 0)
        log("read", path)
        fault(Op.READ, path)?.let { return it }
        if (path in folders) return FileResult.AlreadyExists(path, "a folder is in the way")
        val bytes = files[path] ?: return FileResult.NotFound(path)
        val from = minOf(offset, bytes.size.toLong()).toInt()
        return FileResult.Ok(bytes.copyOfRange(from, minOf(bytes.size.toLong(), from.toLong() + length).toInt()))
    }

    private fun fault(
        op: Op,
        path: Path,
    ) = faults[op]?.invoke(path)

    private fun log(
        call: String,
        path: Path,
    ) {
        journal?.add("$call $path")
    }

    private inner class Writer(
        private val path: Path,
    ) : FileWriter {
        private var closed = false
        private val opened = power

        private fun dead(): FileResult.Failed? = if (opened != power) FileResult.IoError(path, "power cut") else null

        override suspend fun write(
            offset: Long,
            bytes: ByteArray,
            startIndex: Int,
            endIndex: Int,
        ): FileResult<Unit> {
            check(!closed) { "the writer for $path is closed" }
            require(offset >= 0 && startIndex in 0..endIndex && endIndex <= bytes.size)
            dead()?.let { return it }
            fault(Op.WRITE, path)?.let { return it }
            val current = files[path] ?: return FileResult.NotFound(path)
            val end = offset + (endIndex - startIndex)
            require(end <= MAX_FILE)
            val grown = if (end > current.size) current.copyOf(end.toInt()) else current
            bytes.copyInto(grown, offset.toInt(), startIndex, endIndex)
            files[path] = grown
            return FileResult.Ok(Unit)
        }

        override suspend fun size(): FileResult<Long> {
            check(!closed) { "the writer for $path is closed" }
            fault(Op.SIZE, path)?.let { return it }
            val current = files[path] ?: return FileResult.NotFound(path)
            return FileResult.Ok(current.size.toLong())
        }

        override suspend fun sync(): FileResult<Unit> {
            check(!closed) { "the writer for $path is closed" }
            dead()?.let { return it }
            fault(Op.SYNC, path)?.let { return it }
            files[path]?.let { durable[path] = it.copyOf() }
            syncs++
            return FileResult.Ok(Unit)
        }

        override fun close() {
            if (!closed) {
                closed = true
                openWriters--
            }
            closeFault(path)?.let { throw it }
        }
    }

    private companion object {
        const val MAX_FILE = 16L * 1024 * 1024
    }
}
