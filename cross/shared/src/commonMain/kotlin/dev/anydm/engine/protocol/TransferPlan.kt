package dev.anydm.engine.protocol

/**
 * What [Protocol.plan] decided to fetch: at least one file, each with a distinct index and path. It keeps its own copy
 * of the list, so the protocol can't change a plan the runner has already checked.
 */
class TransferPlan(
    files: List<PlannedFile>,
) {
    val files: List<PlannedFile> = files.toList()

    init {
        require(this.files.isNotEmpty()) { "a plan needs at least one file" }
        require(
            this.files
                .map { it.index }
                .toSet()
                .size == this.files.size,
        ) { "file indexes must differ" }
        require(
            this.files
                .map { it.path }
                .toSet()
                .size == this.files.size,
        ) { "file paths must differ" }
    }

    override fun equals(other: Any?): Boolean = other is TransferPlan && files == other.files

    override fun hashCode(): Int = files.hashCode()

    override fun toString(): String = "TransferPlan(files=$files)"
}
