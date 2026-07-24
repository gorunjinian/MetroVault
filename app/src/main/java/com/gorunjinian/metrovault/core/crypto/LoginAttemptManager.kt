package com.gorunjinian.metrovault.core.crypto

import android.content.Context
import android.content.SharedPreferences

import android.os.SystemClock
import androidx.core.content.edit
import com.gorunjinian.metrovault.core.logging.AppLog

/**
 * Enforces rate limiting on login attempts to prevent brute force attacks
 *
 * Security strategy:
 * - Exponential backoff: delays increase with each failed attempt
 * - Persistent storage: survives app restart
 * - Progressive delays: 0s → 0s → 30s → 1m → 5m → 15m → 1h
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
        // Monotonic-clock mirror of the lockout. The device is air-gapped, so
        // the wall clock can be freely advanced to skip lockouts; elapsedRealtime
        // can't be manipulated without a reboot.
        private const val KEY_LOCKOUT_UNTIL_ELAPSED = "lockout_until_elapsed"
        private const val KEY_ELAPSED_AT_WRITE = "elapsed_at_write"

        // Progressive delay strategy (OWASP recommended)
        // Delays start at 3rd attempt to allow 2 free retries
        private val LOCKOUT_DELAYS = listOf(
            0L,           // 1st attempt: no delay
            0L,           // 2nd attempt: no delay
            30_000L,      // 3rd attempt: 30 seconds
            60_000L,      // 4th attempt: 1 minute
            300_000L,     // 5th attempt: 5 minutes
            900_000L,     // 6th attempt: 15 minutes
            3600_000L     // 7+ attempts: 1 hour
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
        val isPermanent = newAttempts >= MAX_ATTEMPTS_BEFORE_PERMANENT_LOCK
        val delay = if (isPermanent) {
            PERMANENT_LOCKOUT_DURATION
        } else {
            val delayIndex = (newAttempts - 1).coerceIn(0, LOCKOUT_DELAYS.size - 1)
            LOCKOUT_DELAYS[delayIndex]
        }

        val lockoutUntil = System.currentTimeMillis() + delay
        val nowElapsed = SystemClock.elapsedRealtime()
        prefs.edit {
            putInt(KEY_FAILED_ATTEMPTS, newAttempts)
            putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
            putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis())
            putLong(KEY_LOCKOUT_UNTIL_ELAPSED, nowElapsed + delay)
            putLong(KEY_ELAPSED_AT_WRITE, nowElapsed)
        }

        if (isPermanent) {
            AppLog.w(TAG) { "SECURITY: Permanent lockout triggered after $newAttempts attempts" }
        } else {
            AppLog.w(TAG) { "Failed attempt #$newAttempts. Locked out for ${delay / 1000}s" }
        }

        return lockoutUntil
    }

    /**
     * Checks if currently locked out
     *
     * Takes the stricter of the wall-clock and monotonic-clock deadlines so
     * advancing the device clock cannot skip a lockout. After a reboot the
     * monotonic clock resets and only the wall-clock deadline applies.
     *
     * @return Remaining lockout time in milliseconds (0 if not locked)
     */
    fun getRemainingLockoutTime(): Long {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        val wallRemaining = (lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0L)

        val elapsedAtWrite = prefs.getLong(KEY_ELAPSED_AT_WRITE, -1L)
        val nowElapsed = SystemClock.elapsedRealtime()
        val elapsedRemaining = if (elapsedAtWrite in 0..nowElapsed) {
            (prefs.getLong(KEY_LOCKOUT_UNTIL_ELAPSED, 0) - nowElapsed).coerceAtLeast(0L)
        } else {
            0L // reboot since last write - monotonic deadline no longer meaningful
        }

        return maxOf(wallRemaining, elapsedRemaining)
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
        AppLog.d(TAG) { "Login attempts reset after successful authentication" }
    }

    /**
     * Gets the current failed attempt count
     */
    fun getFailedAttemptCount(): Int {
        return prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
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
