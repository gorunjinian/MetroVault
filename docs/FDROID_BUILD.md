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
> option chosen here — it removes any binary-provenance question and enables the reproducible
> build configured in §5 (F-Droid publishes the APK under this project's own signing key).

---

## 2. The secp256k1 dependency (pinned & verified)

- MetroVault depends on `fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.24.0`
  (`gradle/libs.versions.toml` → `secp256k1 = "0.24.0"`).
- Upstream: https://github.com/ACINQ/secp256k1-kmp — **Apache-2.0**.
- Release tag **`v0.24.0`** → commit `e9d5c95f85bd326f3f6a5d4fd075ec11510de472`
  (latest release; the tag's `build.gradle.kts` declares `version = "0.24.0"`, so building
  the tag reproduces the exact `0.24.0` artifact MetroVault already uses).
- Bundled C library: **bitcoin-core/secp256k1** (**MIT**), included as a git submodule at
  `native/secp256k1`, pinned at tag `v0.24.0` to commit
  `6e2c8bc4ecdc6e71dbe7a368f360d8d453ce435d` (= upstream release **v0.8.0**).
- Toolchain pinned by the upstream build files:
  - **Android NDK 28.2.13676358** (= release **r28c**)
  - **CMake 3.31.5**

### Compact precomputed tables

Unlike the stock Maven Central artifact, MetroVault's from-source build passes
`-DSECP256K1_ECMULT_WINDOW_SIZE=4 -DSECP256K1_ECMULT_GEN_KB=2` to the libsecp256k1 CMake
build (sed-injected into `native/build.gradle.kts`'s `CMAKE_DEFAULT_OPTS`). This shrinks the
precomputed generator-multiplication tables from ~1,136 KB to ~2.5 KB of `.rodata` per ABI —
about **4.2 MB off the APK** across the four ABIs (the tables are high-entropy EC points and
barely compress). Both values are supported, upstream-CI-tested configurations: the
pre-generated table sources cover every window size 2..15 and all three gen-table sizes
(2/22/86 KiB), so no table regeneration is involved. Signatures are byte-identical
(deterministic nonces) and signing stays constant-time; the only cost is a small
constant-factor slowdown of one-shot operations — the window-15 default is tuned for
sustained block validation (Bitcoin Core), not a signing device.

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
git checkout v0.24.0
git submodule update --init --recursive    # bitcoin-core/secp256k1 @ 6e2c8bc (v0.8.0)

# For a byte-reproducible .so, drop the path-derived GNU build-id (see §5):
echo 'target_link_options( secp256k1-jni PRIVATE -Wl,--build-id=none )' \
  >> jni/android/src/main/CMakeLists.txt

# APK size: compact precomputed ecmult tables (see §2):
sed -i 's/-DBUILD_SHARED_LIBS=OFF/-DBUILD_SHARED_LIBS=OFF -DSECP256K1_ECMULT_WINDOW_SIZE=4 -DSECP256K1_ECMULT_GEN_KB=2/' \
  native/build.gradle.kts

# Requires: JDK 17, Android SDK, NDK r28c (28.2.13676358), CMake 3.31.5, bash
./gradlew :jni:android:publishToMavenLocal
# -> installs fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.24.0 into ~/.m2
```

MetroVault then resolves the locally-built artifact (identical coordinates `…:0.24.0`) instead
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
Binaries: https://github.com/gorunjinian/MetroVault/releases/download/v%v/MetroVault-%v-release.apk

Builds:
  - versionName: 3.9.5
    versionCode: 8
    commit: 78d66b5f70039d608349e451699e92ffedd7fab7
    subdir: app
    sudo:
      - apt-get update
      - apt-get install -y make g++ cmake
    gradle:
      - yes
    srclibs:
      - Secp256k1Kmp@v0.24.0
    prebuild:
      - sed -i -e '/foojay/d' ../settings.gradle.kts
      - echo 'target_link_options( secp256k1-jni PRIVATE -Wl,--build-id=none )' >> $$Secp256k1Kmp$$/jni/android/src/main/CMakeLists.txt
      - sed -i 's/-DBUILD_SHARED_LIBS=OFF/-DBUILD_SHARED_LIBS=OFF -DSECP256K1_ECMULT_WINDOW_SIZE=4 -DSECP256K1_ECMULT_GEN_KB=2/' $$Secp256k1Kmp$$/native/build.gradle.kts
      - sed -i 's/mavenCentral()/mavenLocal(); mavenCentral()/' ../settings.gradle.kts
    build:
      - sdkmanager "cmake;3.31.5"
      - pushd $$Secp256k1Kmp$$
      - gradle :jni:android:publishToMavenLocal
      - popd
    ndk: r28c

AllowedAPKSigningKeys: 1245554ceb17cea21e9912af7bf60d38d716f5884d4b3664e5338462cc76fd03

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
CurrentVersion: 3.9.5
CurrentVersionCode: 8
```

> The live fdroiddata file keeps the earlier `Builds` entries (versionCodes 5–7, on
> `Secp256k1Kmp@v0.23.0` without the table sed) above this one — historical blocks are never
> rewritten. Only the sample block for the current release is shown here.

> Shown unwrapped for readability. The committed file is **`rewritemeta`-canonical**: the long
> `Binaries` URL, the `echo` line and the table-size `sed` line are folded onto continuation
> lines (ruamel breaks a plain scalar at the first space after column 80, continuing at the
> item's content indent), and `Binaries:` carries a **mandatory trailing space** (`rewritemeta`
> re-adds it if removed). The line breaks are cosmetic — YAML folds them back to single
> commands.

Notes:
- The recipe splits into **`prebuild` (source patches)** and **`build` (build commands)** — the
  layout F-Droid review prefers. The scanner runs between them, so it sees fully-patched source
  but none of the build artifacts.
- `sudo: apt-get install make g++ cmake` — the build image (debian trixie) ships no host build
  tools. `cmake` + `make` satisfy ACINQ's `build-android.sh` (it calls **bare `cmake`** with
  the default Make generator); `g++` covers host config checks. (`sudo` is NOPASSWD.)
- **`prebuild` — source patches (before the scanner):**
  - the `foojay` strip removes the `org.gradle.toolchains.foojay-resolver-convention` plugin (a
    network JDK auto-downloader) from `settings.gradle.kts`. It's unused (no toolchain blocks),
    so removal is safe — the standard fdroiddata fix.
  - the `echo … -Wl,--build-id=none …` line patches the JNI CMake so the linker emits **no GNU
    build-id**. Required for reproducible builds: the build-id is a SHA1 the linker derives from
    the `.so`'s path-bearing DWARF debug info, so it leaks the absolute build path and *survives
    stripping* (strip doesn't recompute it) — making the otherwise byte-identical `.so` differ by
    build path. Dropping it makes the stripped `.so` byte-identical regardless of where it is
    built. The app's release CI (`.github/reproducible-build.sh`) appends the **identical** line;
    both sides must match or the published APK won't reproduce.
  - the `ECMULT_WINDOW_SIZE=4` / `ECMULT_GEN_KB=2` sed shrinks libsecp256k1's precomputed
    multiplication tables (~1.1 MB → ~2.5 KB of `.rodata` per ABI; ~4.2 MB off the APK) — see
    §2 "Compact precomputed tables". The app's release CI applies the **identical** sed; both
    sides must match or the published APK won't reproduce.
  - the `mavenLocal(); mavenCentral()` sed makes the app's gradle build resolve the from-source
    artifact ahead of Maven Central. One line — a literal `\n` in the YAML breaks it. Not
    committed to this repo.
- **`build` — build commands (after the scanner, so artifacts stay out of the scan):**
  - `sdkmanager "cmake;3.31.5"` installs the **SDK** CMake (bundles `ninja`). This is required
    for AGP's `externalNativeBuild`, which needs the *exact* pinned version (`3.31.5`) present
    **in the SDK** — AGP does **not** auto-install cmake, and the apt cmake (a different patch
    version, on `PATH`) does not satisfy it. Symptom if missing: `[CXX1300] CMake '3.31.5' was
    not found in SDK, PATH, or by cmake.dir`. So there are **two** cmake consumers: apt cmake
    for `build-android.sh`, SDK cmake for AGP.
  - `gradle :jni:android:publishToMavenLocal` builds the secp256k1 AAR (with the from-source
    `.so`) into `~/.m2`. Use F-Droid's `gradle` wrapper, not the project's `./gradlew`.
- `Binaries` + `AllowedAPKSigningKeys` enable the **reproducible build**: F-Droid rebuilds the
  recipe, compares it to the GitHub release APK, and — when they match — publishes that APK under
  this project's own signing key (cert SHA-256 `1245554c…`) instead of F-Droid's. This requires
  the app to drop AGP's "Dependency metadata" signing block (`dependenciesInfo { includeInApk =
  false }` in `app/build.gradle.kts`) and the build-id patch above; with both, the from-source
  build reproduces byte-for-byte.
- `$$Secp256k1Kmp$$` is the srclib checkout path provided by F-Droid.
- `commit:` is the full immutable commit hash (not the tag) — fdroiddata policy; it guards
  against tag mutation, and must point at the exact commit the published `Binaries` APK was built
  from.
- `versionCode`/`versionName` must be **literal** in `app/build.gradle.kts` `defaultConfig`
  (not via top-level `val`s), or `checkupdates` can't parse the tag — the prerequisite for
  `Tags`/`Version` auto-update. For each release, keep `changelogs/<versionCode>.txt` aligned
  with that release's `versionCode`.
- With `AutoUpdateMode: Version`, fdroidbot copies this `Builds` entry forward for each new tag,
  bumping only `versionName`/`versionCode`/`commit`. Edit it by hand only when a build input
  changes — the `Secp256k1Kmp@v0.24.0` pin, the NDK (`r28c`), or the prebuild/build steps.
  The v3.9.5 release is exactly such a case: the srclib moved to `@v0.24.0` and the
  table-size sed joined `prebuild`, so its build block had to be added by hand (an MR), not
  left to fdroidbot's copy-forward. If fdroidbot has already opened its copy-forward MR for
  the tag, the hand-written MR supersedes it — the copied block (old srclib pin, no table sed)
  would build but fail the `Binaries` reproducibility comparison.

---

## 6. F-Droid build environment (confirmed)

The fdroiddata build job runs in `registry.gitlab.com/fdroid/fdroiddata:buildserver-trixie`
(debian trixie). It provides the JDK, `sdkmanager`, the NDK, and gradle — but **not** `cmake`,
`make`, `ninja`, or `g++`, so the recipe installs them (`sudo: apt-get install make g++ cmake`
plus `sdkmanager "cmake;3.31.5"` for the SDK CMake AGP requires). `sudo` is available
(NOPASSWD). NDK `r28c` (28.2.13676358) is provided via the recipe's `ndk:` field. Build steps
invoke F-Droid's `gradle` wrapper, not the project's `./gradlew`.

Build-step ordering in fdroiddata is `prebuild` → **scanner** → `build` → the app's gradle
build. Source patches (the `foojay` strip, the build-id CMake patch, and the `mavenLocal()`
injection) go in `prebuild` so the scanner sees fully-patched source; the build commands (CMake
install + secp256k1 `publishToMavenLocal`) go in `build`, keeping build artifacts out of the
scan. The scanner also auto-removes `gradle-wrapper.jar` and `gradle-daemon-jvm.properties`
(informational — F-Droid substitutes its own verified gradle wrapper).

The recipe targets every fdroiddata CI job: `rewritemeta`, `lint`, `schema`, `checkupdates`,
`check app` (the binary scanner — which also drove the `dependenciesInfo` change in the app to
drop AGP's "Dependency metadata" signing block), and `fdroid build` (including the
reproducibility comparison against `Binaries`).

---

## 7. Licensing

| Component | License | Compatible with GPL-3.0-or-later |
|-----------|---------|----------------------------------|
| MetroVault (this app) | GPL-3.0-or-later | — |
| ACINQ secp256k1-kmp (wrapper) | Apache-2.0 | Yes |
| bitcoin-core/secp256k1 (C library) | MIT | Yes |
| bbqr-kotlin, bcur-kotlin | MIT | Yes |
