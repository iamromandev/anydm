package dev.anydm.engine.core

/** A hash a finished file can be checked against (HTTP-14), with the length of its hex form. */
enum class ChecksumAlgorithm(
    val hexLength: Int,
) {
    MD5(32),
    SHA1(40),
    SHA256(64),
}

/** What a finished file must hash to: the user's word, or the server's (`Digest`). [hex] is lowercase. */
data class Checksum(
    val algorithm: ChecksumAlgorithm,
    val hex: String,
) {
    init {
        require(hex.length == algorithm.hexLength && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "a $algorithm checksum is ${algorithm.hexLength} lowercase hex digits"
        }
    }

    /** `md5:<hex>`, `sha1:<hex>` or `sha256:<hex>`: what [parse] reads back, and how the store keeps it. */
    override fun toString(): String = "${algorithm.name.lowercase()}:$hex"

    companion object {
        private val names =
            mapOf(
                "md5" to ChecksumAlgorithm.MD5,
                "sha1" to ChecksumAlgorithm.SHA1,
                "sha-1" to ChecksumAlgorithm.SHA1,
                "sha256" to ChecksumAlgorithm.SHA256,
                "sha-256" to ChecksumAlgorithm.SHA256,
            )

        /**
         * `<name>:<hex>` or `<name>=<hex>` (md5, sha1, sha-1, sha256, sha-256, any case), or bare hex whose length names
         * the algorithm; hex in any case. Null for anything else.
         */
        fun parse(text: String): Checksum? {
            val trimmed = text.trim()
            val separator = trimmed.indexOfFirst { it == ':' || it == '=' }
            val (algorithm, hex) =
                if (separator < 0) {
                    (ChecksumAlgorithm.entries.firstOrNull { it.hexLength == trimmed.length } ?: return null) to trimmed
                } else {
                    (names[trimmed.substring(0, separator).lowercase()] ?: return null) to trimmed.substring(separator + 1)
                }
            val lower = hex.lowercase()
            if (lower.length != algorithm.hexLength || !lower.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return Checksum(algorithm, lower)
        }
    }
}
