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

### `metadata/com.gorunjinian.metrovault.yml`

```yaml
Categories:
  - Wallet
License: GPL-3.0-or-later
AuthorName: Gorun Jinian
WebSite: https://gorunjinian.com
SourceCode: https://github.com/gorunjinian/MetroVault
IssueTracker: https://github.com/gorunjinian/MetroVault/issues

AutoName: Metro Vault

RepoType: git
Repo: https://github.com/gorunjinian/MetroVault.git

Builds:
  - versionName: 3.8.6
    versionCode: 5
    commit: v3.8.6
    subdir: app
    sudo:
      - apt-get update
      - apt-get install -y make g++
    gradle:
      - yes
    srclibs:
      - Secp256k1Kmp@v0.23.0
    prebuild:
      - sdkmanager "cmake;3.31.6"
      - export PATH="$ANDROID_HOME/cmake/3.31.6/bin:$PATH"
      - sed -i 's/version = "3.31.5"/version = "3.31.6"/' $$Secp256k1Kmp$$/jni/android/build.gradle.kts
      - pushd $$Secp256k1Kmp$$
      - ./gradlew :jni:android:publishToMavenLocal
      - popd
      - sed -i 's/mavenCentral()/mavenLocal(); mavenCentral()/' ../settings.gradle.kts
      - sed -i -e '/foojay/d' ../settings.gradle.kts
    ndk: r28c

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
CurrentVersion: 3.8.6
CurrentVersionCode: 5
```

Notes (every line is verified green in the fdroiddata CI):
- `sudo: apt-get install make g++` — the build image (debian trixie) ships no host build
  tools; `make` drives CMake's Make generator in `build-android.sh`, `g++` covers host config
  checks. (`sudo` is available NOPASSWD in the buildserver.)
- `sdkmanager "cmake;3.31.6"` + the `PATH` export — installs the **SDK** CMake (which bundles
  `ninja`, used by AGP's `externalNativeBuild`) and puts it on `PATH` for `build-android.sh`'s
  bare `cmake`. `3.31.6` is the SDK version closest to secp256k1-kmp's pinned `3.31.5`; the
  `sed` repoints AGP's pin to it.
- `$$Secp256k1Kmp$$` is the srclib checkout path provided by F-Droid.
- The `mavenLocal(); mavenCentral()` sed makes the app resolve the from-source artifact ahead
  of Maven Central, **only during the F-Droid build** (not committed to this repo). Keep it on
  **one line** — a literal `\n` in the YAML string breaks the prebuild.
- The `foojay` sed strips `org.gradle.toolchains.foojay-resolver-convention` from
  `settings.gradle.kts`; F-Droid's scanner rejects it (network JDK auto-download). The plugin
  is unused (no toolchain blocks), so removal is safe — this is the standard fdroiddata fix.
- `versionCode`/`versionName` must be **literal** in `app/build.gradle.kts` `defaultConfig`
  (not via top-level `val`s), or `checkupdates` can't parse the tag — that's the prerequisite
  for `Tags`/`Version` auto-update. For each release, keep `changelogs/<versionCode>.txt`
  aligned with that release's `versionCode`.

---

## 6. F-Droid build environment (confirmed)

The fdroiddata build job runs in `registry.gitlab.com/fdroid/fdroiddata:buildserver-trixie`
(debian trixie). It provides the JDK, `sdkmanager`, the NDK, and gradle — but **not** `cmake`,
`make`, `ninja`, or `g++`, so the recipe installs them (`sudo: apt-get install make g++` and
`sdkmanager "cmake;3.31.6"`, which bundles `ninja`). `sudo` is available (NOPASSWD). NDK
`r28c` (28.2.13676358) is provided via the recipe's `ndk:` field.

The F-Droid scanner runs **after** `prebuild`, so the `sed` edits to `settings.gradle.kts`
(inject `mavenLocal()`, strip `foojay`) are in place before scanning. The scanner also
auto-removes `gradle-wrapper.jar` and `gradle-daemon-jvm.properties` (informational — F-Droid
substitutes its own verified gradle wrapper).

This recipe is **verified green** across all fdroiddata CI jobs: `rewritemeta`, `lint`,
`schema`, `checkupdates`, and `fdroid build`.

---

## 7. Licensing

| Component | License | Compatible with GPL-3.0-or-later |
|-----------|---------|----------------------------------|
| MetroVault (this app) | GPL-3.0-or-later | — |
| ACINQ secp256k1-kmp (wrapper) | Apache-2.0 | Yes |
| bitcoin-core/secp256k1 (C library) | MIT | Yes |
| bbqr-kotlin, bcur-kotlin | MIT | Yes |
