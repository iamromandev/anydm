package dev.anydm.desktop.files

import dev.anydm.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/** One file to fetch: its URL (with the key in its query) and the name it's saved under. */
data class SaveTarget(
    val url: String,
    val name: String,
)

class SaveFailed(
    message: String,
) : Exception(message)

/** What a Save fetches: a torrent's selected files one by one when there are several, else the one file. */
fun saveTargets(
    task: Task,
    fileUrl: (id: String, index: Int?) -> String,
): List<SaveTarget> {
    val selected = task.files?.filter { it.selected }.orEmpty()
    if (selected.size > 1) return selected.map { SaveTarget(fileUrl(task.id, it.index), it.path.substringAfterLast('/')) }
    return listOf(SaveTarget(fileUrl(task.id, null), task.filename ?: task.title))
}

/** [name] in [dir], or "name (1).ext", "name (2).ext" … when it's taken (spec: never overwrite). */
fun uniqueTarget(
    dir: Path,
    name: String,
): Path {
    val first = dir.resolve(name)
    if (!Files.exists(first)) return first
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (Files.exists(dir.resolve("$stem ($n)$ext"))) n += 1
    return dir.resolve("$stem ($n)$ext")
}

fun downloadsDir(home: String = System.getProperty("user.home")): Path = Path.of(home, "Downloads")

/** Fetches a finished file into a folder, through a `.part` that's removed if anything goes wrong. */
class Saver(
    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
) {
    suspend fun save(
        target: SaveTarget,
        dir: Path,
        onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> },
    ): Path =
        withContext(Dispatchers.IO) {
            Files.createDirectories(dir)
            val destination = uniqueTarget(dir, target.name)
            val part = destination.resolveSibling("${destination.fileName}.part")
            try {
                val response = http.send(HttpRequest.newBuilder(URI(target.url)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() !in 200..299) {
                    response.body().close()
                    throw SaveFailed("The server answered ${response.statusCode()}")
                }
                val length = response.headers().firstValueAsLong("content-length")
                val total = if (length.isPresent) length.asLong else null
                response.body().use { input ->
                    Files.newOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
                Files.move(part, destination)
                destination
            } catch (error: Exception) {
                Files.deleteIfExists(part)
                throw error
            }
        }
}
