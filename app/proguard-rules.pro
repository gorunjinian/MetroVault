
# R8 rules for MetroVault.
#
# Sizing note: every `-keep ... { *; }` below is a hole in R8's shrinker. Widen
# one and the whole subtree survives — the blanket Tink keep this file used to
# carry was worth ~1.2 MB of dex on its own. Keep the rules as narrow as the
# reflection/JNI they exist to protect.

# =====================================
# Google Tink (via androidx.security-crypto / EncryptedSharedPreferences)
# =====================================
# NO blanket keep here on purpose. security-crypto's own consumer proguard.txt
# is "Intentionally empty ... this library is safe to shrink", and tink-android
# ships the one rule it genuinely needs (a `<fields>` keep on shaded-protobuf
# GeneratedMessageLite subclasses) in META-INF/proguard/protobuf.pro, which AGP
# applies automatically. Key managers reach Tink's registry through direct
# AeadConfig/DeterministicAeadConfig.register() calls, so R8 traces them.
# Keeping com.google.crypto.tink.** dragged in jwt, signature, hybrid, prf,
# streamingaead, daead and the full proto/protobuf surface — none of it used.
-dontwarn com.google.crypto.tink.**

# javax.annotation classes (referenced by Tink, absent on Android)
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# =====================================
# androidx.security-crypto
# =====================================
# Also shrink-safe per its own (empty) consumer rules; no keep needed.
-dontwarn androidx.security.crypto.**

# =====================================
# ZXing (QR generation + scanner decoder configuration)
# =====================================
# Enum classes are read reflectively by ZXing's hint maps, so their fields stay.
-keepclassmembers enum com.google.zxing.BarcodeFormat { *; }
-keepclassmembers enum com.google.zxing.EncodeHintType { *; }
-keepclassmembers enum com.google.zxing.DecodeHintType { *; }
-keepclassmembers enum com.google.zxing.qrcode.decoder.ErrorCorrectionLevel { *; }
-dontwarn com.google.zxing.**

# =====================================
# Secp256k1 native JNI library (Bitcoin crypto)
# =====================================
# Deliberately left broad. The native side resolves Secp256k1CFunctions,
# NativeSecp256k1Util$AssertFailException and Secp256k1Context by name, and the
# whole package is only ~10 KB of dex — not worth trading a name-lookup crash in
# the signing path for. Do not narrow this to save bytes.
-keep class fr.acinq.secp256k1.** { *; }
-keepclassmembers class fr.acinq.secp256k1.** { *; }
-dontwarn fr.acinq.secp256k1.**

# Keep native method declarations (JNI name-based lookup) app-wide.
-keepclasseswithmembernames class * {
    native <methods>;
}

# =====================================
# Logging — defense-in-depth strip. The `AppLog` wrapper is already
# compile-time eliminated in release via `if (BuildConfig.DEBUG)` + `inline`.
# These rules strip any residual `android.util.Log` calls (libraries,
# missed call sites, or third-party code that survives minification) and
# any `AppLog` calls that somehow weren't inlined.
# =====================================
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
    public static boolean isLoggable(java.lang.String, int);
}

-assumenosideeffects class com.gorunjinian.metrovault.core.logging.AppLog {
    public *** v(...);
    public *** d(...);
    public *** i(...);
    public *** w(...);
    public *** e(...);
}
