package dev.anydm.engine.net

/** What a command does to a saved proxy's password (NET-15). */
sealed interface PasswordChange {
    /** Leaves the password as it is. */
    data object Keep : PasswordChange

    /** Deletes the password from the keychain. */
    data object Clear : PasswordChange

    /** Saves [password] in the keychain. */
    class Set(
        val password: String,
    ) : PasswordChange {
        override fun toString(): String = "Set(password hidden)"
    }
}

/** What saving a proxy came to. */
sealed interface ProxyCommandResult {
    data class Saved(
        val proxy: ProxyEntry,
    ) : ProxyCommandResult

    /** Another proxy has the name. */
    data object NameTaken : ProxyCommandResult

    /** An update of a proxy that no longer exists. */
    data object NotFound : ProxyCommandResult

    /** The password, or the username it needs, is wrong; nothing changed. */
    data class Invalid(
        val reason: String,
    ) : ProxyCommandResult

    /** Saved, but the proxy has no username now and its old password is still in the keychain, which couldn't be reached. */
    data class PasswordLeft(
        val proxy: ProxyEntry,
    ) : ProxyCommandResult

    /** There is no keychain to keep a password in, or it can't be reached; nothing changed. */
    data class KeychainFailed(
        val reason: String,
    ) : ProxyCommandResult
}
