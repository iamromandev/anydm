package dev.anydm.engine.core

/*
 * Which mirror a job uses (FND-08), derived from its Attempt rows: a mirror whose attempt ended GONE is skipped for the
 * rest of the round. A round ends once every mirror is gone (the job then fails GONE); the next attempt, after a user
 * retry, begins a new one at position 0. A relink doesn't bring a gone mirror back before that.
 */

/** The mirrors skipped in the current round: those whose attempt ended GONE since the round began. */
internal fun goneMirrors(
    mirrors: List<Mirror>,
    attempts: List<Attempt>,
): Set<MirrorId> {
    val ids = mirrors.map { it.id }.toSet()
    val gone = mutableSetOf<MirrorId>()
    for (attempt in attempts) {
        if (ids.isNotEmpty() && gone.containsAll(ids)) gone.clear() // this attempt began a new round
        val mirror = attempt.mirrorId
        if (attempt.errorClass == ErrorClass.GONE && mirror in ids) gone += mirror!!
    }
    return gone
}

/** The mirror a new attempt uses: the first not gone, or the first of all when every mirror is gone; null for none. */
internal fun mirrorToStart(
    mirrors: List<Mirror>,
    attempts: List<Attempt>,
): Mirror? = nextMirror(mirrors, attempts) ?: mirrors.minByOrNull { it.position }

/** The first mirror not gone in this round, or null when every mirror is gone. */
internal fun nextMirror(
    mirrors: List<Mirror>,
    attempts: List<Attempt>,
): Mirror? {
    val gone = goneMirrors(mirrors, attempts)
    return mirrors.filter { it.id !in gone }.minByOrNull { it.position }
}
