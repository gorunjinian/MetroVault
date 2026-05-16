# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/Cellar/android-sdk/24.3.3/tools/proguard/proguard-android.txt


# Keep security-crypto
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Keep ZXing (QR code generation + scanner decoder configuration)
-keep class com.google.zxing.BarcodeFormat { *; }
-keep class com.google.zxing.EncodeHintType { *; }
-keep class com.google.zxing.DecodeHintType { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.qrcode.decoder.ErrorCorrectionLevel { *; }
-keep class com.google.zxing.qrcode.encoder.** { *; }
-keep class com.google.zxing.common.BitMatrix { *; }
-dontwarn com.google.zxing.**

# Keep our crypto classes
-keep class com.gorunjinian.metrovault.crypto.** { *; }
-keep class com.gorunjinian.metrovault.wallet.** { *; }

# Prevent obfuscation of sensitive crypto operations
-keepclassmembers class * {
    @javax.crypto.* *;
}

# =====================================
# Google Tink (used by EncryptedSharedPreferences)
# =====================================
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# javax.annotation classes (required by Tink)
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# Keep Tink proto classes
-keep class com.google.crypto.tink.proto.** { *; }

# =====================================
# Secp256k1 Native JNI Library (Bitcoin crypto)
# =====================================
-keep class fr.acinq.secp256k1.** { *; }
-keepclassmembers class fr.acinq.secp256k1.** { *; }
-dontwarn fr.acinq.secp256k1.**

# Keep native method declarations
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
