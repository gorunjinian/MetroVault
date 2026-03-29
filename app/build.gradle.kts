import com.android.build.api.dsl.ApplicationExtension
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val appVersionName = "3.7.0"
val appVersionCode = 3

configure<ApplicationExtension> {
    namespace = "com.gorunjinian.metrovault"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gorunjinian.metrovault"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing configuration
    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))

                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            } else {
                val envStorePath = System.getenv("KEYSTORE_PATH")
                if (!envStorePath.isNullOrEmpty()) {
                    storeFile = file(envStorePath)
                    storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                    keyAlias = System.getenv("KEY_ALIAS") ?: ""
                    keyPassword = System.getenv("KEY_PASSWORD") ?: ""
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }

        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

base.archivesName.set("MetroVault-$appVersionName")

dependencies {
    // Core Android
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.splashscreen)

    // Material 3
    implementation(libs.material)
    implementation(libs.compose.material.icons.extended)

    // Reorderable drag-and-drop for LazyColumn
    implementation(libs.reorderable)

    // Jetpack Compose (BOM manages all Compose artifact versions)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)

    // Crypto & Security
    implementation(libs.security.crypto)
    implementation(libs.biometric)

    // QR Code
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    //BBQr and BC-UR implementations
    implementation(libs.bcur.kotlin)
    implementation(libs.bbqr)

    // CBOR
    implementation(libs.cbor)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.process)

    // Bitcoin
    implementation(kotlin("stdlib"))
    implementation(libs.secp256k1.android)

    implementation(libs.window)
    implementation(libs.compose.ui.text)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
}
