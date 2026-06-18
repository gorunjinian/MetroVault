#!/bin/bash
# Reproducible release build for F-Droid parity. Runs inside
# registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie. Builds secp256k1
# from source (matching the fdroiddata recipe) so the released APK is
# byte-identical to F-Droid's reproducible build.
set -euxo pipefail
export ANDROID_HOME=/opt/android-sdk

# Host build tools the image lacks (build-android.sh calls bare cmake/make).
apt-get update && apt-get install -y make g++ cmake

# Pinned toolchain identical to the fdroiddata recipe. Disable pipefail for this
# line so `yes` getting SIGPIPE (exit 141) when sdkmanager closes its stdin does
# not fail the build; sdkmanager's own exit status still gates success.
set +o pipefail
yes | sdkmanager "ndk;28.2.13676358" "cmake;3.31.5" >/dev/null
set -o pipefail
export PATH="$ANDROID_HOME/cmake/3.31.5/bin:$PATH"
git config --global --add safe.directory '*'

# Build secp256k1-kmp v0.23.0 from source -> mavenLocal.
git clone --recursive --branch v0.23.0 --depth 1 \
  https://github.com/ACINQ/secp256k1-kmp.git /tmp/secp256k1-kmp
# Reproducibility: drop the GNU build-id from the JNI .so. The linker derives it
# from the (path-bearing) debug info, so without this the stripped lib differs by
# build path. With it, the stripped .so is byte-identical regardless of path,
# matching F-Droid. The fdroiddata recipe MUST apply this same line.
echo 'target_link_options( secp256k1-jni PRIVATE -Wl,--build-id=none )' \
  >> /tmp/secp256k1-kmp/jni/android/src/main/CMakeLists.txt
( cd /tmp/secp256k1-kmp && gradle :jni:android:publishToMavenLocal )

# Resolve the from-source artifact ahead of Maven Central.
sed -i 's/mavenCentral()/mavenLocal(); mavenCentral()/' settings.gradle.kts

# Sign with the release keystore (kept in /tmp, out of the mounted workspace).
echo "$KEYSTORE_BASE64" | base64 -d > /tmp/release.keystore
KEYSTORE_PATH=/tmp/release.keystore ./gradlew assembleRelease
