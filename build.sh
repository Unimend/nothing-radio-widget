#!/bin/sh
set -eu

# macOS/Linux build for the dependency-free Java widget.
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
UNITY_ANDROID="/Applications/Unity/2022.3.52f1c1/PlaybackEngines/AndroidPlayer"
SDK_ROOT=${ANDROID_SDK_ROOT:-"$UNITY_ANDROID/SDK"}
JDK_ROOT=${JAVA_HOME:-"$UNITY_ANDROID/OpenJDK"}
BUILD_TOOLS=${BUILD_TOOLS_VERSION:-34.0.0}
BT="$SDK_ROOT/build-tools/$BUILD_TOOLS"
PLATFORM="$SDK_ROOT/platforms/android-34/android.jar"
OUT="$PROJECT_DIR/build"

export JAVA_HOME="$JDK_ROOT"
export PATH="$JAVA_HOME/bin:$PATH"

for tool in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$JDK_ROOT/bin/javac" "$JDK_ROOT/bin/jar" "$JDK_ROOT/bin/keytool" "$PLATFORM"; do
    if [ ! -e "$tool" ]; then
        echo "Missing build dependency: $tool" >&2
        exit 1
    fi
done

rm -rf "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/7] Compile resources"
"$BT/aapt2" compile --dir "$PROJECT_DIR/res" -o "$OUT/compiled/res.zip"

echo "[2/7] Link resources and explicit SDK metadata"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    -R "$OUT/compiled/res.zip" --java "$OUT/gen" -A "$PROJECT_DIR/assets" \
    --auto-add-overlay --min-sdk-version 23 --target-sdk-version 31 \
    --version-code 6 --version-name 2.2.0

echo "[3/7] Compile Java"
find "$PROJECT_DIR/src" "$OUT/gen" -type f -name '*.java' -print0 \
    | xargs -0 "$JDK_ROOT/bin/javac" -encoding UTF-8 -source 8 -target 8 \
        -classpath "$PLATFORM" -d "$OUT/classes"

echo "[4/7] Convert classes to DEX"
find "$OUT/classes" -type f -name '*.class' -print0 \
    | xargs -0 "$BT/d8" --release --min-api 23 --lib "$PLATFORM" --output "$OUT/dex"

echo "[5/7] Package DEX"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && "$JDK_ROOT/bin/jar" -uf "$OUT/unsigned.apk" classes.dex)

echo "[6/7] Align"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[7/7] Sign"
if [ ! -f "$OUT/debug.keystore" ]; then
    "$JDK_ROOT/bin/keytool" -genkeypair -keystore "$OUT/debug.keystore" \
        -alias androiddebugkey -storepass android -keypass android -keyalg RSA \
        -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign --ks "$OUT/debug.keystore" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$OUT/radiowidget.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$OUT/radiowidget.apk"

echo "BUILD OK: $OUT/radiowidget.apk"
