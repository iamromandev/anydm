package dev.anydm.engine.http

/** A login for HTTP auth (HTTP-11). Kept in memory only; [toString] never shows the password. */
data class Credentials(
    val user: String,
    val password: String,
) {
    init {
        require(':' !in user) { "a user name can't hold ':'" }
        require(sendable(user) && sendable(password)) { "credentials can't hold CR, LF or NUL" }
    }

    override fun toString(): String = "Credentials(user=$user, password=***)"

    private companion object {
        fun sendable(value: String) = value.none { it == '\r' || it == '\n' || it == '\u0000' }
    }
}
