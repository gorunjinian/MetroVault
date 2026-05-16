package com.gorunjinian.metrovault.core.logging

import android.util.Log
import com.gorunjinian.metrovault.BuildConfig

// Compile-time gated logger. `BuildConfig.DEBUG` is `const val false` in
// release builds, and these functions are `inline`, so kotlinc folds the
// `if` branch — and the lambda body — to dead code at every release call
// site. No strings are constructed, no tags survive. ProGuard rules in
// `proguard-rules.pro` provide defense-in-depth.
//
// Always use the lambda form so argument computation is gated too:
//   AppLog.d(TAG) { "Signed input $i" }
//   AppLog.e(TAG, throwable) { "Failed to parse PSBT" }
//
// The @Suppress silences a false-positive IDE inspection: AGP writes the
// debug BuildConfig.DEBUG as `Boolean.parseBoolean("true")` to defeat
// constant folding, but Android Studio resolves the symbol against the
// release variant (literal `false`) and warns that the `if` is always
// false. At runtime the debug call returns true; in release the inline
// + const-false combo makes kotlinc eliminate the branch and the lambda.
@Suppress("KotlinConstantConditions")
object AppLog {
    inline fun v(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.v(tag, message())
    }
    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }
    inline fun i(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(tag, message())
    }
    inline fun w(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.w(tag, message())
    }
    inline fun w(tag: String, throwable: Throwable, message: () -> String) {
        if (BuildConfig.DEBUG) Log.w(tag, message(), throwable)
    }
    inline fun e(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.e(tag, message())
    }
    inline fun e(tag: String, throwable: Throwable, message: () -> String) {
        if (BuildConfig.DEBUG) Log.e(tag, message(), throwable)
    }
}
