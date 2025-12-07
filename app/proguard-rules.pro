# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/Cellar/android-sdk/24.3.3/tools/proguard/proguard-android.txt

# Keep Bouncycastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep security-crypto
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Keep ZXing
-keep class com.google.zxing.** { *; }
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
