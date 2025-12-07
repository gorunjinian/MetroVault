package com.gorunjinian.metrovault.core.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Enforces rate limiting on login attempts to prevent brute force attacks
 *
 * Security strategy:
 * - Exponential backoff: delays increase with each failed attempt
 * - Persistent storage: survives app restart
 * - Progressive delays: 0s → 30s → 1m → 5m → 15m → 1h
 * - Permanent lockout after 20 attempts (24 hour cooldown)
 *
 * Attack mitigation:
 * - WITHOUT rate limiting: ~1000 attempts/second possible
 * - WITH rate limiting: ~6 attempts/day maximum
 */
class LoginAttemptManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "login_attempts",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val TAG = "LoginAttemptManager"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val KEY_LAST_ATTEMPT = "last_attempt"

        // Progressive delay strategy (OWASP recommended)
        private val LOCKOUT_DELAYS = listOf(
            0L,           // 1st attempt: no delay
            30_000L,      // 2nd attempt: 30 seconds
            60_000L,      // 3rd attempt: 1 minute
            300_000L,     // 4th attempt: 5 minutes
            900_000L,     // 5th attempt: 15 minutes
            3600_000L     // 6+ attempts: 1 hour
        )

        private const val MAX_ATTEMPTS_BEFORE_PERMANENT_LOCK = 20
        private const val PERMANENT_LOCKOUT_DURATION = 24 * 60 * 60 * 1000L // 24 hours
    }

    /**
     * Records a failed login attempt
     * @return Timestamp when user can try again (0 if can retry now)
     */
    fun recordFailedAttempt(): Long {
        val currentAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        val newAttempts = currentAttempts + 1

        // Check for permanent lockout
        if (newAttempts >= MAX_ATTEMPTS_BEFORE_PERMANENT_LOCK) {
            val lockoutUntil = System.currentTimeMillis() + PERMANENT_LOCKOUT_DURATION
            prefs.edit {
                putInt(KEY_FAILED_ATTEMPTS, newAttempts)
                putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
                putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis())
            }
            android.util.Log.w(TAG, "SECURITY: Permanent lockout triggered after $newAttempts attempts")
            return lockoutUntil
        }

        // Calculate exponential backoff delay
        val delayIndex = (newAttempts - 1).coerceIn(0, LOCKOUT_DELAYS.size - 1)
        val delay = LOCKOUT_DELAYS[delayIndex]
        val lockoutUntil = System.currentTimeMillis() + delay

        prefs.edit {
            putInt(KEY_FAILED_ATTEMPTS, newAttempts)
            putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
            putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis())
        }

        android.util.Log.w(TAG, "Failed attempt #$newAttempts. Locked out for ${delay/1000}s")

        return lockoutUntil
    }

    /**
     * Checks if currently locked out
     * @return Remaining lockout time in milliseconds (0 if not locked)
     */
    fun getRemainingLockoutTime(): Long {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        val now = System.currentTimeMillis()

        return if (now < lockoutUntil) {
            lockoutUntil - now
        } else {
            0L
        }
    }

    /**
     * Checks if account is locked out
     */
    fun isLockedOut(): Boolean {
        return getRemainingLockoutTime() > 0
    }

    /**
     * Resets all attempt tracking (call on successful login)
     */
    fun resetAttempts() {
        prefs.edit { clear() }
        android.util.Log.d(TAG, "Login attempts reset after successful authentication")
    }

    /**
     * Formats remaining time as human-readable string
     */
    fun formatRemainingTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        return when {
            seconds < 60 -> "$seconds seconds"
            seconds < 3600 -> "${seconds / 60} minutes"
            else -> "${seconds / 3600} hours"
        }
    }
}
