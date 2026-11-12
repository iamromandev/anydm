package dev.anydm.desktop

enum class MenuAction { Pause, Resume, Open, ShowInFolder, Remove }

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

fun canPause(t: List<DownloadItem>): Boolean = t.any { it.status == DownloadStatus.Downloading }

fun canResume(t: List<DownloadItem>): Boolean = t.any { it.status == DownloadStatus.Paused }

fun allFinished(t: List<DownloadItem>): Boolean = t.isNotEmpty() && t.all { it.status == DownloadStatus.Finished }

private fun move(
    list: List<DownloadItem>,
    ids: Set<String>,
    from: DownloadStatus,
    to: DownloadStatus,
): List<DownloadItem> = list.map { if (it.id in ids && it.status == from) it.copy(status = to) else it }

fun pause(
    list: List<DownloadItem>,
    ids: Set<String>,
): List<DownloadItem> = move(list, ids, DownloadStatus.Downloading, DownloadStatus.Paused)

fun resume(
    list: List<DownloadItem>,
    ids: Set<String>,
): List<DownloadItem> = move(list, ids, DownloadStatus.Paused, DownloadStatus.Downloading)

fun remove(
    list: List<DownloadItem>,
    ids: Set<String>,
): List<DownloadItem> = list.filter { it.id !in ids }

fun targetsFor(
    selected: Set<String>,
    rowId: String,
): Set<String> = if (rowId in selected) selected else setOf(rowId)

fun prune(
    selected: Set<String>,
    list: List<DownloadItem>,
): Set<String> = selected intersect list.map { it.id }.toSet()

fun menuActions(t: List<DownloadItem>): List<MenuAction> =
    buildList {
        if (t.isEmpty()) return@buildList
        if (canPause(t)) add(MenuAction.Pause)
        if (canResume(t)) add(MenuAction.Resume)
        if (allFinished(t)) {
            add(MenuAction.Open)
            add(MenuAction.ShowInFolder)
        }
        add(MenuAction.Remove)
    }

fun selectionLabel(n: Int): String = "$n selected"

fun removeTitle(n: Int): String = if (n == 1) "Remove 1 download?" else "Remove $n downloads?"
