import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val targetSdkValue = 36
val minSdkValue = 26

android {
    namespace = "com.gorunjinian.metrovault"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gorunjinian.metrovault"
        minSdk = minSdkValue
        targetSdk = targetSdkValue
        versionCode = 8
        versionName = "3.9.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // drop AGP's "Dependency metadata" APK signing block. The F-Droid
    // scanner rejects it as an extra signing block, and it can carry
    // non-deterministic data that would break reproducible builds.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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

    // English-only app: keep just "en" so the dozens of unused transitive
    // AndroidX/Material translations are stripped from the APK.
    @Suppress("UnstableApiUsage")
    androidResources {
        localeFilters += listOf("en")
    }

    testOptions {
        unitTests {
            // Let JVM unit tests exercise production code that calls android.util.Log
            // (AppLog, which is enabled when BuildConfig.DEBUG is true) by returning
            // default values from un-mocked Android framework calls instead of throwing.
            isReturnDefaultValues = true
        }
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
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",

                // Per-artifact license copies AndroidX ships under
                // META-INF/androidx/**; ~22 KB compressed, none of it read at
                // runtime. Attribution lives in the About screen and LICENSE.
                "META-INF/androidx/**",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/com/android/build/gradle/**",

                // kotlin-reflect metadata. Only kotlin.reflect.full reads these
                // and nothing in the app uses it, so the descriptors are dead
                // weight. Re-check if kotlin-reflect is ever added.
                "kotlin/**",
                "**/*.kotlin_builtins",
                "**/*.kotlin_metadata",

                // kotlinx-coroutines debug-agent probe table; debug tooling only.
                "DebugProbesKt.bin"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

composeCompiler {
    // Per-composable stability + recomposition reports under build/compose_compiler/.
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}

base.archivesName.set("MetroVault-${android.defaultConfig.versionName}")

dependencies {
    // Core Android
    implementation(libs.core.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.splashscreen)

    // appcompat is not used directly (MainActivity is a FragmentActivity and
    // there are no XML layouts) — androidx.biometric pulls it in and asks for
    // 1.2.0. Declared here only to pin the resolved version forward.
    implementation(libs.appcompat)

    // NOTE: com.google.android.material (View-based Material Components) is
    // deliberately absent. It was only ever the parent of Theme.MetroVault, and
    // it dragged in cardview, constraintlayout, coordinatorlayout,
    // dynamicanimation, recyclerview, transition and viewpager2 — all unused by
    // a Compose-only app. Compose Material 3 (below) is a separate artifact.

    // Reorderable drag-and-drop for LazyColumn
    implementation(libs.reorderable)

    // Jetpack Compose (BOM manages all Compose artifact versions)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
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

    implementation(libs.compose.ui.text)

    // Removed as unused and not needed transitively by anything else:
    //  - androidx.compose.ui:ui-tooling-preview — no @Preview in main source
    //    (add it back as debugImplementation if previews are reintroduced)
    //  - androidx.window — no WindowMetrics/WindowInfoTracker usage

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.secp256k1.jvm.test)
    testImplementation(libs.json)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
}
