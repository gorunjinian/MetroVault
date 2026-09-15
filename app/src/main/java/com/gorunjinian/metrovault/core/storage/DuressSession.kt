package com.gorunjinian.metrovault.core.storage

/**
 * Process-wide state of a duress session: the fake session the app opens
 * after a duress unlock (duress password or duress fingerprint) has destroyed
 * all real data. See [SecureStorage.enterDuressSession].
 *
 * While [isActive], [SecureStorage] reads and writes the two vault "files"
 * through [mainPrefs] and [decoyPrefs] instead of the encrypted files on
 * disk, so the throwaway wallet shown to the attacker, and anything they
 * create, exists only in RAM. [end] drops all of it; it runs whenever the
 * session is cleared (backgrounding, lock, another wipe), after which the
 * app has no main password and the next screen is first-time setup.
 *
 * In-session password prompts (seed export, change password, ...) verify
 * against whatever record [SecureStorage] placed in [decoyPrefs] at start:
 * the duress password's own verifier when one existed on disk. When none
 * did, which is the biometric-duress-without-duress-password case, there is
 * no password the user could possibly know, so [acceptsAnyPassword] lets
 * every non-empty entry through until an in-session password change stores
 * a real record.
 */
object DuressSession {

    @Volatile
    var isActive: Boolean = false
        private set

    /** RAM stand-ins for the main and decoy vault files. */
    val mainPrefs = InMemorySharedPreferences()
    val decoyPrefs = InMemorySharedPreferences()

    /** True only while [isActive]; [start] sets it and [end] clears it. */
    @Volatile
    var acceptsAnyPassword: Boolean = false
        private set

    /**
     * Starts a fresh session with empty stores.
     *
     * @param acceptAnyPassword true when no verifier is available for
     *   in-session prompts; the caller stores one into [decoyPrefs] otherwise.
     */
    fun start(acceptAnyPassword: Boolean) {
        mainPrefs.wipe()
        decoyPrefs.wipe()
        acceptsAnyPassword = acceptAnyPassword
        isActive = true
    }

    /** A real verifier now exists; stop accepting arbitrary passwords. */
    fun passwordRecordStored() {
        acceptsAnyPassword = false
    }

    /** Ends the session and drops everything it held. Safe to call when inactive. */
    fun end() {
        isActive = false
        acceptsAnyPassword = false
        mainPrefs.wipe()
        decoyPrefs.wipe()
    }
}
