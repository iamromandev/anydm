package dev.anydm.engine.torrent

/**
 * A torrent's own seeding limit (TOR-05): [Inherit] takes the global setting, [Off] means no limit of this kind for this
 * torrent whatever the setting says, [At] stops the seed at that value.
 */
sealed interface SeedRule<out T> {
    data object Inherit : SeedRule<Nothing>

    data object Off : SeedRule<Nothing>

    data class At<out T>(
        val value: T,
    ) : SeedRule<T>
}

/** The limit that applies: this rule's value, none for [SeedRule.Off], or [default] for [SeedRule.Inherit]. */
fun <T : Any> SeedRule<T>.effective(default: T?): T? =
    when (this) {
        SeedRule.Inherit -> default
        SeedRule.Off -> null
        is SeedRule.At -> value
    }
