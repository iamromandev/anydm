package dev.anydm.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.TorrentActionResult
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.torrent.DEFAULT_PRIORITY
import dev.anydm.engine.torrent.MAX_FILE_PRIORITY
import dev.anydm.engine.torrent.SeedRule
import dev.anydm.engine.torrent.TorrentTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A torrent with more files than this opens with its folders collapsed. */
internal const val COLLAPSE_ABOVE_FILES = 100

/** The most file and folder rows the tree draws at once; "Show more" raises it by this much. */
internal const val MAX_TREE_ROWS = 300

/** Folders nested deeper than this are drawn at this indentation. */
private const val MAX_INDENT_DEPTH = 8

internal const val MAX_SEED_RATIO = 1_000.0

/** Ten years. */
internal const val MAX_SEED_MINUTES = 5_256_000

private const val SKIP_PRIORITY = 0

private const val SKIP_NOTE = "Takes effect the next time the torrent runs, and restarts its progress counters."

private val RatioPattern = Regex("""\d{1,9}(\.\d{1,9})?""")
private val MinutesPattern = Regex("""\d{1,9}""")

/** [value] as plain decimal digits (never an exponent), so [parseSeedRatio] reads it back. */
internal fun plainDecimal(value: Double): String =
    java.math
        .BigDecimal(value.toString())
        .stripTrailingZeros()
        .toPlainString()

/** A ratio limit: plain decimal digits, above 0 and at most [MAX_SEED_RATIO]; null for anything else. */
internal fun parseSeedRatio(text: String): Double? {
    val t = text.trim()
    if (!RatioPattern.matches(t)) return null
    return t.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= MAX_SEED_RATIO }
}

/** A minutes limit: plain digits, from 1 to [MAX_SEED_MINUTES]; null for anything else. */
internal fun parseSeedMinutes(text: String): Int? {
    val t = text.trim()
    if (!MinutesPattern.matches(t)) return null
    return t.toIntOrNull()?.takeIf { it in 1..MAX_SEED_MINUTES }
}

/**
 * What the polled detail and the user's unsent or unconfirmed changes look like for one torrent. It outlives the tabs, so
 * switching tabs does not lose a draft or show an older saved value.
 */
internal class TorrentLive(
    val loaded: Boolean,
    val result: TorrentDetailResult?,
    val seed: SeedState,
    val files: FilesState,
    val scope: CoroutineScope,
) {
    val detail: TorrentDetailResult.Detail? get() = result as? TorrentDetailResult.Detail
}

/** An editor for one rule: whether the Value chip is open and what is typed. */
internal class RuleEdit {
    var editing by mutableStateOf(false)
    var text by mutableStateOf("")
}

/** The torrent's two seed rules as shown: the saved ones, with a change shown at once until a later poll confirms it. */
internal class SeedState(
    private val scope: CoroutineScope,
) {
    var ratio by mutableStateOf<SeedRule<Double>?>(null)
        private set
    var minutes by mutableStateOf<SeedRule<Int>?>(null)
        private set
    val ratioEdit = RuleEdit()
    val minutesEdit = RuleEdit()
    var polls = 0
    private var pending = 0
    private var seq = 0
    private var holdUntil = 0

    val loaded get() = ratio != null && minutes != null

    private fun showRatio(
        rule: SeedRule<Double>,
        writeText: Boolean = true,
    ) {
        ratio = rule
        if (writeText) (rule as? SeedRule.At)?.let { ratioEdit.text = plainDecimal(it.value) }
    }

    private fun showMinutes(
        rule: SeedRule<Int>,
        writeText: Boolean = true,
    ) {
        minutes = rule
        if (writeText) (rule as? SeedRule.At)?.let { minutesEdit.text = it.value.toString() }
    }

    /** Takes what the engine answered, unless a save is in flight or just made (an older poll could undo it) or it is being edited. */
    fun sync(
        detail: TorrentDetailResult.Detail,
        polls: Int,
    ) {
        this.polls = polls
        if (pending > 0 || (loaded && polls < holdUntil)) return
        if (!ratioEdit.editing) showRatio(detail.seedRatio)
        if (!minutesEdit.editing) showMinutes(detail.seedMinutes)
    }

    fun commit(
        newRatio: SeedRule<Double>,
        newMinutes: SeedRule<Int>,
        save: suspend (SeedRule<Double>, SeedRule<Int>) -> CommandResult?,
    ) {
        val beforeRatio = ratio ?: return
        val beforeMinutes = minutes ?: return
        val mine = ++seq
        pending++
        // Only the rule that changed gets its text written; a draft in the other editor stays.
        showRatio(newRatio, writeText = newRatio != beforeRatio)
        showMinutes(newMinutes, writeText = newMinutes != beforeMinutes)
        scope.launch {
            val result =
                try {
                    save(newRatio, newMinutes)
                } finally {
                    pending--
                }
            if (result is CommandResult.Done) {
                holdUntil = polls + 2
            } else if (mine == seq) {
                // Only the latest save goes back; a newer one already carries both rules.
                showRatio(beforeRatio, writeText = !ratioEdit.editing)
                showMinutes(beforeMinutes, writeText = !minutesEdit.editing)
            }
        }
    }
}

/** The Files tab as shown: the tree's folds and size, and priority changes shown at once until a later poll confirms them. */
internal class FilesState(
    private val scope: CoroutineScope,
) {
    var local by mutableStateOf<List<Int>?>(null)
        private set
    var toggled by mutableStateOf(emptySet<String>())
    var limit by mutableIntStateOf(MAX_TREE_ROWS)

    /** The row that has the "applies next run" note, if any. */
    var note by mutableStateOf<String?>(null)
        private set
    private var polls = 0
    private var pending = 0
    private var seq = 0
    private var holdUntil = 0

    /** A poll came in: from then on the engine's answer is shown again, unless a change is in flight or was just made. */
    fun settle(polls: Int) {
        this.polls = polls
        if (pending == 0 && polls >= holdUntil) local = null
    }

    fun change(
        next: List<Int>,
        noteKey: String?,
        save: suspend (List<Int>) -> CommandResult?,
    ) {
        val mine = ++seq
        pending++
        local = next
        note = noteKey
        scope.launch {
            val result =
                try {
                    save(next)
                } finally {
                    pending--
                }
            if (result is CommandResult.Done) {
                holdUntil = polls + 2
            } else if (mine == seq) {
                local = null
                note = null
            }
        }
    }
}

@Composable
private fun Line(
    text: String,
    tag: String? = null,
    modifier: Modifier = Modifier,
) = Text(text, style = MaterialTheme.typography.bodySmall, modifier = if (tag == null) modifier else modifier.testTag(tag))

/** Column titles over rows laid out with the same weights. */
@Composable
private fun Header(vararg columns: Pair<String, Float>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        columns.forEach { (title, weight) ->
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(weight),
            )
        }
    }
}

/** One line that is cut with an ellipsis; the full text is in a tooltip and the semantics. */
@Composable
private fun Clipped(
    text: String,
    tag: String,
    modifier: Modifier,
) = Tip(text, "$tag-tip", modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = text }.testTag(tag),
    )
}

private const val REANNOUNCE_WEIGHT = 2f

@Composable
private fun Unavailable(live: TorrentLive) =
    if (live.loaded) {
        Line("Torrent details unavailable (the torrent is not in the session).", "torrent-unavailable")
    } else {
        Line("Loading…", "torrent-loading")
    }

@Composable
internal fun TorrentOverview(
    s: DownloadSnapshot,
    live: TorrentLive,
    model: AppModel,
) {
    val summary = s.torrent
    val done = progress(s)
    Line(
        "Progress ${done?.let { "${(it * 100).toInt()}%" } ?: "—"} · ${formatBytes(s.downloaded)} of ${s.total?.let(::formatBytes) ?: "?"}",
        "overview-progress",
    )
    Line("ETA ${eta(s) ?: "—"}", "overview-eta")
    if (summary == null) {
        Line("Not in the torrent session.", "overview-no-session")
    } else {
        Line("Ratio ${summary.ratio?.let { "%.2f".format(it) } ?: "—"}", "overview-ratio")
        Line("Peers ${summary.peers} · Seeds ${summary.seeds}", "overview-peers")
        Line("Down ${formatSpeed(summary.downloadRate)} · Up ${formatSpeed(summary.uploadRate)}", "overview-rates")
        Line("Uploaded ${formatBytes(summary.uploaded)}", "overview-uploaded")
        Line("Availability ${"%.2f".format(summary.availability)}", "overview-availability")
        val status = s.download.status
        if ((status == DownloadStatus.SEEDING || status == DownloadStatus.COMPLETED) && summary.finishedSeconds > 0) {
            Line("Seeding for ${formatDuration(summary.finishedSeconds)}", "overview-seeding-time")
        }
    }
    TorrentControls(s, live, model)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TorrentControls(
    s: DownloadSnapshot,
    live: TorrentLive,
    model: AppModel,
) {
    val id = s.download.id
    val ready = live.detail != null
    var recheckStarted by remember(id) { mutableStateOf(false) }
    Line("Seeding", "overview-controls")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (JobCommand.STOP_SEEDING.appliesTo(s)) {
            TextButton(
                onClick = { model.run(JobCommand.STOP_SEEDING) },
                enabled = ready,
                modifier = Modifier.testTag("torrent-stop-seeding"),
            ) { Text("Stop seeding") }
        }
        TextButton(
            onClick = {
                recheckStarted = false
                live.scope.launch { recheckStarted = model.recheckTorrent(id) == TorrentActionResult.Done }
            },
            enabled = ready,
            modifier = Modifier.testTag("torrent-recheck"),
        ) { Text("Force recheck") }
        TextButton(
            onClick = { live.scope.launch { model.reannounceTorrent(id, null) } },
            enabled = ready,
            modifier = Modifier.testTag("torrent-reannounce"),
        ) { Text("Reannounce all") }
    }
    if (recheckStarted) Line("Recheck started.", "torrent-recheck-started")
    if (!ready) Unavailable(live)
    if (live.seed.loaded) SeedRules(id, live, model)
}

/** The torrent's own ratio and time limits; each is Default (the global one), Off, or a value set with its button. */
@Composable
private fun SeedRules(
    id: DownloadId,
    live: TorrentLive,
    model: AppModel,
) {
    val seed = live.seed
    val ratio = seed.ratio ?: return
    val minutes = seed.minutes ?: return
    val enabled = live.detail != null
    RuleEditor("seed-ratio", "Stop at ratio", ratio, seed.ratioEdit, enabled, ::parseSeedRatio, "Enter a number above 0 (at most 1000).") {
        seed.commit(it, minutes) { r, m -> model.setSeedRules(id, r, m) }
    }
    RuleEditor(
        "seed-minutes",
        "Stop after minutes",
        minutes,
        seed.minutesEdit,
        enabled,
        ::parseSeedMinutes,
        "Enter a whole number of minutes above 0.",
    ) {
        seed.commit(ratio, it) { r, m -> model.setSeedRules(id, r, m) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> RuleEditor(
    tag: String,
    label: String,
    rule: SeedRule<T>,
    edit: RuleEdit,
    enabled: Boolean,
    parse: (String) -> T?,
    errorText: String,
    onCommit: (SeedRule<T>) -> Unit,
) {
    val editing = edit.editing
    val atValue = rule is SeedRule.At || editing
    Line(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(
            selected = rule == SeedRule.Inherit && !editing,
            onClick = {
                edit.editing = false
                onCommit(SeedRule.Inherit)
            },
            enabled = enabled,
            label = { Text("Default") },
            modifier = Modifier.testTag("$tag-inherit"),
        )
        FilterChip(
            selected = rule == SeedRule.Off && !editing,
            onClick = {
                edit.editing = false
                onCommit(SeedRule.Off)
            },
            enabled = enabled,
            label = { Text("Off") },
            modifier = Modifier.testTag("$tag-off"),
        )
        FilterChip(
            selected = atValue,
            onClick = { edit.editing = true },
            enabled = enabled,
            label = { Text("Value") },
            modifier = Modifier.testTag("$tag-at"),
        )
    }
    if (atValue) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = edit.text,
                onValueChange = { edit.text = it },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("$tag-field"),
            )
            val value = parse(edit.text)
            TextButton(
                onClick = {
                    value?.let {
                        edit.editing = false
                        onCommit(SeedRule.At(it))
                    }
                },
                enabled = enabled && value != null,
                modifier = Modifier.testTag("$tag-set"),
            ) { Text("Set") }
        }
        if (edit.text.isNotBlank() && parse(edit.text) == null) Line(errorText, "$tag-error")
    }
}

internal sealed interface TreeNode {
    val name: String
}

internal class TreeFolder(
    override val name: String,
    val path: String,
) : TreeNode {
    val children: MutableList<TreeNode> = mutableListOf()
    internal val byPath: MutableMap<String, TreeFolder> = HashMap()

    /** Every file index under this folder, found once. */
    val files: List<Int> by lazy { children.flatMap { if (it is TreeFolder) it.files else listOf((it as TreeLeaf).index) } }
}

internal class TreeLeaf(
    override val name: String,
    val index: Int,
) : TreeNode

/** The folder tree for files given as (index, path) pairs; folders are found by hash, so a big torrent builds in one pass. */
internal fun buildTree(paths: List<Pair<Int, String>>): TreeFolder {
    val root = TreeFolder("", "")
    paths.sortedBy { it.first }.forEach { (index, path) ->
        val parts = path.split('/').filter { it.isNotEmpty() }.ifEmpty { listOf(path) }
        var at = root
        parts.dropLast(1).forEach { part ->
            val folderPath = if (at.path.isEmpty()) part else "${at.path}/$part"
            val parent = at
            at = parent.byPath.getOrPut(folderPath) { TreeFolder(part, folderPath).also { parent.children += it } }
        }
        at.children += TreeLeaf(parts.last(), index)
    }
    return root
}

internal sealed interface TreeRow {
    val depth: Int

    class FolderRow(
        val folder: TreeFolder,
        override val depth: Int,
        val open: Boolean,
    ) : TreeRow

    class FileRow(
        val index: Int,
        val name: String,
        override val depth: Int,
    ) : TreeRow
}

/** The rows to draw, at most [limit], and how many files lie in the rows left out. */
internal class FlatTree(
    val rows: List<TreeRow>,
    val hiddenFiles: Int,
)

/** Walks the open folders into a flat list; a torrent of more than [COLLAPSE_ABOVE_FILES] files has folders closed unless toggled. */
internal fun flattenTree(
    root: TreeFolder,
    toggled: Set<String>,
    collapsedByDefault: Boolean,
    limit: Int,
): FlatTree {
    val rows = ArrayList<TreeRow>()
    var hidden = 0

    fun add(row: TreeRow) {
        if (rows.size < limit) {
            rows += row
        } else if (row is TreeRow.FileRow) {
            hidden++
        } else if (row is TreeRow.FolderRow && !row.open) {
            hidden += row.folder.files.size
        }
    }

    fun walk(
        folder: TreeFolder,
        depth: Int,
    ) {
        folder.children.forEach { node ->
            when (node) {
                is TreeFolder -> {
                    val open = (node.path in toggled) == collapsedByDefault
                    add(TreeRow.FolderRow(node, depth, open))
                    if (open) walk(node, depth + 1)
                }

                is TreeLeaf -> {
                    add(TreeRow.FileRow(node.index, node.name, depth))
                }
            }
        }
    }
    walk(root, 0)
    return FlatTree(rows, hidden)
}

private fun indent(depth: Int) = (minOf(depth, MAX_INDENT_DEPTH) * 8).dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TorrentFiles(
    s: DownloadSnapshot,
    live: TorrentLive,
    model: AppModel,
) {
    if (s.files.isEmpty()) return Line("Waiting for metadata.", "torrent-no-metadata")
    val id = s.download.id
    val detail = live.detail
    val st = live.files
    val count = (s.files.maxOf { it.file.index }) + 1
    val stored = List(count) { detail?.priorities?.getOrNull(it) ?: DEFAULT_PRIORITY }
    val shown = st.local?.takeIf { it.size == count } ?: stored

    fun change(
        key: String,
        files: List<Int>,
        priority: Int,
    ) {
        val next = shown.toMutableList().also { list -> files.forEach { list[it] = priority } }
        val crossesSkip = files.any { (shown[it] == SKIP_PRIORITY) != (priority == SKIP_PRIORITY) }
        st.change(next, key.takeIf { crossesSkip }) { model.setTorrentFiles(id, it) }
    }

    // Keyed on paths only, so a progress update does not rebuild the tree.
    val paths = s.files.map { it.file.index to (it.file.placedPath ?: it.file.path) }
    val tree = remember(paths) { buildTree(paths) }
    val collapsedByDefault = s.files.size > COLLAPSE_ABOVE_FILES
    val flat = remember(tree, st.toggled, st.limit, collapsedByDefault) { flattenTree(tree, st.toggled, collapsedByDefault, st.limit) }
    val byIndex = s.files.associateBy { it.file.index }

    if (detail == null) Unavailable(live)
    flat.rows.forEach { row ->
        when (row) {
            is TreeRow.FolderRow -> {
                val node = row.folder
                val key = "folder:${node.path}"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).padding(start = indent(row.depth))) {
                        TextButton(
                            onClick = { st.toggled = if (node.path in st.toggled) st.toggled - node.path else st.toggled + node.path },
                            modifier = Modifier.testTag("torrent-folder-toggle-${node.path}"),
                        ) { Text("${if (row.open) "▾" else "▸"} ${node.name}", style = MaterialTheme.typography.labelMedium) }
                    }
                    PriorityChips("torrent-folder-chips-${node.path}", node.files.map { shown[it] }, detail != null, Modifier) {
                        change(key, node.files, it)
                    }
                }
                if (st.note == key) Line(SKIP_NOTE, "torrent-skip-note")
            }

            is TreeRow.FileRow -> {
                val key = "file:${row.index}"
                val f = byIndex.getValue(row.index)
                val size = f.file.size?.let(::formatBytes) ?: "size unknown"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Line(
                        "${row.name} — ${formatBytes(f.written)} of $size",
                        "torrent-file-${row.index}",
                        Modifier.weight(1f).padding(start = indent(row.depth)),
                    )
                    PriorityChips("torrent-file-priority-${row.index}", listOf(shown[row.index]), detail != null, Modifier) {
                        change(key, listOf(row.index), it)
                    }
                }
                if (st.note == key) Line(SKIP_NOTE, "torrent-skip-note")
            }
        }
    }
    if (flat.hiddenFiles > 0) {
        Line("… ${flat.hiddenFiles} more files not shown", "torrent-files-more")
        TextButton(onClick = { st.limit += MAX_TREE_ROWS }, modifier = Modifier.testTag("torrent-files-show-more")) { Text("Show more") }
    }
}

/** Skip, Normal and High for [priorities] (one file's, or a folder's); a chip is selected when all of them are at its level. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriorityChips(
    tag: String,
    priorities: List<Int>,
    enabled: Boolean,
    modifier: Modifier,
    onPick: (Int) -> Unit,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            Triple("skip", "Skip", SKIP_PRIORITY),
            Triple("normal", "Normal", DEFAULT_PRIORITY),
            Triple("high", "High", MAX_FILE_PRIORITY),
        ).forEach { (name, label, value) ->
            val level = { p: Int ->
                if (p ==
                    SKIP_PRIORITY
                ) {
                    SKIP_PRIORITY
                } else if (p == MAX_FILE_PRIORITY) {
                    MAX_FILE_PRIORITY
                } else {
                    DEFAULT_PRIORITY
                }
            }
            FilterChip(
                selected = priorities.isNotEmpty() && priorities.all { level(it) == value },
                onClick = { onPick(value) },
                enabled = enabled,
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("$tag-$name"),
            )
        }
    }
}

@Composable
internal fun TorrentTrackers(
    s: DownloadSnapshot,
    live: TorrentLive,
    model: AppModel,
) {
    val id = s.download.id
    val detail = live.detail
    TextButton(
        onClick = { live.scope.launch { model.reannounceTorrent(id, null) } },
        enabled = detail != null,
        modifier = Modifier.testTag("tracker-reannounce-all"),
    ) { Text("Reannounce all") }
    if (detail == null) return Unavailable(live)
    val trackers = detail.detail.trackers
    if (trackers.isEmpty()) {
        return Line(if (s.files.isEmpty()) "Waiting for metadata." else "No trackers.", "trackers-none")
    }
    Header("URL" to 3f, "Status" to 2f, "Scrape" to 2f, "Next" to 2f, "" to REANNOUNCE_WEIGHT)
    trackers.forEachIndexed { i, t ->
        // URL | status | scrape | next announce | reannounce, so the width is used; a cell with nothing to say stays empty.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tip(t.url, "tracker-tip-$i", Modifier.weight(3f)) {
                Text(
                    t.url,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = t.url }.testTag("tracker-$i"),
                )
            }
            Line(trackerStatus(t), "tracker-status-$i", Modifier.weight(2f))
            if (t.scrapeSeeds != null || t.scrapePeers != null) {
                Line("Scrape: seeds ${t.scrapeSeeds ?: "?"} · peers ${t.scrapePeers ?: "?"}", "tracker-scrape-$i", Modifier.weight(2f))
            } else {
                Spacer(Modifier.weight(2f))
            }
            val next = if (t.enabled) t.nextAnnounceSeconds else null
            if (next !=
                null
            ) {
                Line("Next announce in ${formatDuration(next)}", "tracker-next-$i", Modifier.weight(2f))
            } else {
                Spacer(Modifier.weight(2f))
            }
            TextButton(
                onClick = { live.scope.launch { model.reannounceTorrent(id, t.url) } },
                enabled = t.enabled,
                modifier = Modifier.weight(REANNOUNCE_WEIGHT).testTag("tracker-reannounce-$i"),
            ) { Text("Reannounce") }
        }
        Line("Tier ${t.tier} · ${if (t.enabled) "enabled" else "disabled"}${if (t.current) " · current" else ""}")
        trackerNote(t)?.let { Line(it, "tracker-message-$i") }
        if (t.fails > 0) Line("Failed announces: ${t.fails}", "tracker-fails-$i")
        HorizontalDivider()
    }
}

/** A tracker's message is its status only when it points at a problem; otherwise it is shown beside the status, which stays Working. */
private fun trackerStatus(t: TorrentTracker): String =
    when {
        t.updating -> "Updating…"
        t.hasProblem() -> t.message!!.trim()
        !t.enabled -> "Disabled"
        t.verified -> "Working"
        else -> "Not announced yet"
    }

private fun trackerNote(t: TorrentTracker): String? = t.message?.takeIf { it.isNotBlank() && !t.updating && !t.hasProblem() }?.trim()

private fun TorrentTracker.hasProblem() = !message.isNullOrBlank() && (fails > 0 || !verified)

@Composable
internal fun TorrentPeers(
    s: DownloadSnapshot,
    live: TorrentLive,
) {
    val summary = s.torrent
    if (summary == null) {
        Line("Not in the torrent session.")
    } else {
        Line("Peers ${summary.peers} · Seeds ${summary.seeds}")
        Line("Down ${formatSpeed(summary.downloadRate)} · Up ${formatSpeed(summary.uploadRate)}")
        Line("Uploaded ${formatBytes(summary.uploaded)} · Ratio ${summary.ratio?.let { "%.2f".format(it) } ?: "—"}")
    }
    when (val detail = live.result) {
        is TorrentDetailResult.Detail -> {
            if (detail.detail.peers.isNotEmpty()) Header("Address" to 3f, "Client" to 3f, "Progress" to 1f, "Rate" to 3f)
            detail.detail.peers.forEachIndexed { i, p ->
                // address | client | progress | rates
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Clipped(p.address, "peer-address-$i", Modifier.weight(3f))
                    Clipped(p.client, "peer-client-$i", Modifier.weight(3f))
                    Line("${(p.progress * 100).toInt()}%", modifier = Modifier.weight(1f))
                    Line("↓${formatSpeed(p.downloadRate)} ↑${formatSpeed(p.uploadRate)}", modifier = Modifier.weight(3f))
                }
            }
            if (detail.detail.peers.isEmpty()) Line("No peers connected.")
        }

        else -> {
            if (live.loaded) Line("Live peers unavailable.", "peers-unavailable") else Line("Loading…", "torrent-loading")
        }
    }
}
