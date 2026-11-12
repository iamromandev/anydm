package dev.anydm.desktop

enum class MenuAction { Pause, Resume, Open, ShowInFolder, Remove, MoveToTop, MoveUp, MoveDown, StartNow }

/** An open row menu: the rows it acts on and the pointer position in window pixels. */
data class RowMenuState(
    val ids: Set<String>,
    val x: Float,
    val y: Float,
)

fun targetsOf(
    list: List<DownloadItem>,
    ids: Set<String>,
): List<DownloadItem> = list.filter { it.id in ids }

fun canPause(t: List<DownloadItem>): Boolean = t.any { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }

fun canResume(t: List<DownloadItem>): Boolean = t.any { it.status == DownloadStatus.Paused }

fun allFinished(t: List<DownloadItem>): Boolean = t.isNotEmpty() && t.all { it.status == DownloadStatus.Finished }

fun targetsFor(
    selected: Set<String>,
    rowId: String,
): Set<String> = if (rowId in selected) selected else setOf(rowId)

fun prune(
    selected: Set<String>,
    list: List<DownloadItem>,
): Set<String> = selected intersect list.map { it.id }.toSet()

fun menuActions(
    t: List<DownloadItem>,
    queue: List<String>,
): List<MenuAction> =
    buildList {
        if (t.isEmpty()) return@buildList
        if (canPause(t)) add(MenuAction.Pause)
        if (canResume(t)) add(MenuAction.Resume)
        val ids = t.map { it.id }.toSet()
        val s = QueueState(t, queue)
        if (moveToTop(s, ids).queue != queue) add(MenuAction.MoveToTop)
        if (moveUp(s, ids).queue != queue) add(MenuAction.MoveUp)
        if (moveDown(s, ids).queue != queue) add(MenuAction.MoveDown)
        if (t.any { it.status == DownloadStatus.Queued }) add(MenuAction.StartNow)
        if (allFinished(t)) {
            add(MenuAction.Open)
            add(MenuAction.ShowInFolder)
        }
        add(MenuAction.Remove)
    }

fun selectionLabel(n: Int): String = "$n selected"

fun removeTitle(n: Int): String = if (n == 1) "Remove 1 download?" else "Remove $n downloads?"
