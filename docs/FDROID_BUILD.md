# Building MetroVault for F-Droid

This document describes how MetroVault is packaged for [F-Droid](https://f-droid.org), with
emphasis on building the **secp256k1 native library from source** so the published app
contains no unverifiable prebuilt cryptographic binaries.

MetroVault is well suited to F-Droid: it is fully open source (GPL-3.0-or-later), has no
internet permission, no analytics/trackers, no proprietary dependencies, and requests only
`CAMERA` (optional) and `USE_BIOMETRIC`.

---

## 1. Native code in the release APK

The release APK ships two native libraries:

| Library | Comes from | Source repo | F-Droid handling |
|---------|-----------|-------------|------------------|
| `libandroidx.graphics.path.so` | `androidx.graphics:graphics-path` (Google's Maven) | AOSP / Jetpack | Standard AndroidX artifact, accepted as-is like any Google/Maven dependency. |
| `libsecp256k1-jni.so` | `fr.acinq.secp256k1:secp256k1-kmp-jni-android` (Maven Central) | https://github.com/ACINQ/secp256k1-kmp | **Built from source** per this document. |

The sibling secp256k1 artifacts MetroVault pulls in transitively — `secp256k1-kmp` and
`secp256k1-kmp-jni` — are **pure Kotlin with no native code**, so they can continue to be
resolved from Maven Central. Only `secp256k1-kmp-jni-android` carries a `.so` that we rebuild.

> Note: ACINQ secp256k1-kmp is FOSS (Apache-2.0) and published on Maven Central, so F-Droid
> may well accept the upstream artifact as-is. Building it from source is the more rigorous
> option chosen here — it removes any binary-provenance question and is a prerequisite for
> reproducible builds.

---

## 2. The secp256k1 dependency (pinned & verified)

- MetroVault depends on `fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.23.0`
  (`gradle/libs.versions.toml` → `secp256k1 = "0.23.0"`).
- Upstream: https://github.com/ACINQ/secp256k1-kmp — **Apache-2.0**.
- Release tag **`v0.23.0`** → commit `a8117f92cb02bb07f7b8051d19bfddce74532b48`
  (latest release; the tag's `build.gradle.kts` declares `version = "0.23.0"`, so building
  the tag reproduces the exact `0.23.0` artifact MetroVault already uses).
- Bundled C library: **bitcoin-core/secp256k1** (**MIT**), included as a git submodule at
  `native/secp256k1`, pinned at tag `v0.23.0` to commit
  `57315a69853c9bd4765fccf20b541d47f1b45ca9`.
- Toolchain pinned by the upstream build files:
  - **Android NDK 28.2.13676358** (= release **r28c**)
  - **CMake 3.31.5**

---

## 3. How the `.so` is built (upstream Gradle wiring)

The native build is fully driven by ACINQ's Gradle setup — no manual shell steps are needed:

1. `:jni:android` (`jni/android/build.gradle.kts`) declares an `externalNativeBuild` CMake
   project (`jni/android/src/main/CMakeLists.txt`) and `ndkVersion = "28.2.13676358"`.
2. Its CMake configure task `dependsOn(":native:buildSecp256k1Android")`, which registers one
   `build-android.sh` invocation per ABI — **arm64-v8a, armeabi-v7a, x86, x86_64** — each
   compiling `bitcoin-core/secp256k1` into a static `libsecp256k1.a`.
3. `CMakeLists.txt` compiles ACINQ's JNI glue
   (`jni/c/src/fr_acinq_secp256k1_Secp256k1CFunctions.c`) and links it against that static lib
   to produce `libsecp256k1-jni.so`.
4. The AAR is assembled and published as `secp256k1-kmp-jni-android`.

---

## 4. Reproducing the build manually

```bash
git clone --recursive https://github.com/ACINQ/secp256k1-kmp.git
cd secp256k1-kmp
git checkout v0.23.0
git submodule update --init --recursive    # bitcoin-core/secp256k1 @ 57315a6

# Requires: JDK 17, Android SDK, NDK r28c (28.2.13676358), CMake 3.31.5, bash
./gradlew :jni:android:publishToMavenLocal
# -> installs fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.23.0 into ~/.m2
```

MetroVault then resolves the locally-built artifact (identical coordinates `…:0.23.0`) instead
of the Maven Central one, by prepending `mavenLocal()` to the repositories.

---

## 5. F-Droid recipe (fdroiddata)

These files live in a fork of https://gitlab.com/fdroid/fdroiddata, **not** in this repo.

### `srclibs/Secp256k1Kmp.yml`

```yaml
RepoType: git
Repo: https://github.com/ACINQ/secp256k1-kmp.git
Prepare: git submodule update --init --recursive
```

### `metadata/com.gorunjinian.metrovault.yml` (build entry)

```yaml
Builds:
  - versionName: 3.8.6          # a NEW tagged release that INCLUDES LICENSE.txt + fastlane/
    versionCode: 5
    commit: v3.8.6
    subdir: app
    srclibs:
      - secp256k1kmp@v0.23.0
    ndk: r28c                    # 28.2.13676358
    gradle:
      - yes
    prebuild:
      # 1) build the secp256k1 native lib from source and publish it to ~/.m2
      - pushd $$secp256k1kmp$$
      - ./gradlew :jni:android:publishToMavenLocal
      - popd
      # 2) make this build prefer the from-source artifact over Maven Central
      - sed -i 's/mavenCentral()/mavenLocal()\n        mavenCentral()/' ../settings.gradle.kts
```

Notes:
- `$$secp256k1kmp$$` is the srclib checkout path provided by F-Droid.
- The `sed` injects `mavenLocal()` **only during the F-Droid build** — it is deliberately not
  committed to this repo, to avoid changing normal/CI dependency resolution.
- The current `v3.8.5` tag predates `LICENSE.txt` and the `fastlane/` metadata. Build a fresh
  tagged release that contains both, and align the `changelogs/<versionCode>.txt` filename
  with the built `versionCode`.

---

## 6. Toolchain notes / residual risk

- F-Droid's buildserver does not pre-provision CMake, and provisions NDKs by release name.
  AGP/Gradle request the versions pinned in secp256k1-kmp's build files (NDK `r28c`,
  CMake `3.31.5`) and the Android SDK manager supplies them.
- **The main thing to validate on the first build** is that these exact NDK/CMake versions are
  obtainable in F-Droid's environment. If one is not, patch the checked-out srclib
  (`jni/android/build.gradle.kts`) in `prebuild` to an available version before publishing.

---

## 7. Licensing

| Component | License | Compatible with GPL-3.0-or-later |
|-----------|---------|----------------------------------|
| MetroVault (this app) | GPL-3.0-or-later | — |
| ACINQ secp256k1-kmp (wrapper) | Apache-2.0 | Yes |
| bitcoin-core/secp256k1 (C library) | MIT | Yes |
| bbqr-kotlin, bcur-kotlin | MIT | Yes |
