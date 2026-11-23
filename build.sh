#!/bin/sh
# Builds build/skylight-switcher.apk: a two- or three-finger swipe switches
# between the Skylight app and the Home Assistant app. Needs a JDK 17 and the Android
# SDK's API 28 platform and build-tools.
#
# Install, then once per device:
#   adb install -r build/skylight-switcher.apk
#   adb shell appops set house.pape.switcher SYSTEM_ALERT_WINDOW allow
#   adb shell appops set house.pape.switcher GET_USAGE_STATS allow
#   adb shell am start -n house.pape.switcher/.MainActivity
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android/sdk}}
[ -d "$SDK" ] || SDK="$LOCALAPPDATA/Android/Sdk"          # Windows default
BT=${BT:-$SDK/build-tools/34.0.0}
ANDROID_JAR=${ANDROID_JAR:-$SDK/platforms/android-28/android.jar}
KEYSTORE=${KEYSTORE:-$HOME/.android/alfred-satellite.keystore}
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"

tool() { for t in "$BT/$1" "$BT/$1.exe" "$BT/$1.bat"; do [ -f "$t" ] && { echo "$t"; return; }; done; echo "missing $1 in $BT" >&2; exit 1; }
AAPT2=$(tool aapt2); D8=$(tool d8); ZIPALIGN=$(tool zipalign); APKSIGNER=$(tool apksigner)

rm -rf build && mkdir -p build/classes build/dex
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp "$ANDROID_JAR" -d build/classes $(find src -name '*.java')
"$D8" --min-api 26 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name '*.class')
"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link -o build/unsigned.apk -I "$ANDROID_JAR" --manifest AndroidManifest.xml build/res.zip
(cd build/dex && jar uMf ../unsigned.apk classes.dex)
"$ZIPALIGN" -f 4 build/unsigned.apk build/aligned.apk
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias alfred \
    -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=alfred-satellite"
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --out build/skylight-switcher.apk build/aligned.apk
ls -l build/skylight-switcher.apk
