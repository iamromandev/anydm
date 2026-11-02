package dev.anydm.desktop

import java.nio.file.Path

/** [FileActions] that touch nothing and record what they were asked; [problem] makes each answer with that text. */
internal class FakeFileActions : FileActions {
    val calls = mutableListOf<String>()
    var problem: String? = null

    private fun record(
        verb: String,
        what: Any,
    ): String? {
        calls += "$verb $what"
        return problem
    }

    override fun open(path: Path) = record("open", path)

    override fun reveal(path: Path) = record("reveal", path)

    override fun copy(text: String) = record("copy", text)

    override fun clearCopied(text: String) {
        record("clearCopied", text)
    }
}
