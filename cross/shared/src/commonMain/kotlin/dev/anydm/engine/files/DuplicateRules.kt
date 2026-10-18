package dev.anydm.engine.files

import dev.anydm.engine.http.cleanUrl

/** The comparisons behind the duplicate check (FIL-05). */
object DuplicateRules {
    /** The same link once redirect wrappers and tracking parameters are gone ([cleanUrl]). */
    fun sameUrl(
        a: String,
        b: String,
    ): Boolean = cleanUrl(a) == cleanUrl(b)

    /**
     * The same name (any case) and the same, known size; and the same validator when both sides have one. A side that
     * doesn't know its size can't match, so a probe that learned nothing never calls a file a duplicate.
     */
    fun sameFile(
        aName: String,
        aSize: Long?,
        aValidator: String?,
        bName: String,
        bSize: Long?,
        bValidator: String?,
    ): Boolean =
        aSize != null &&
            aSize == bSize &&
            aName.equals(bName, ignoreCase = true) &&
            (aValidator == null || bValidator == null || aValidator == bValidator)
}
