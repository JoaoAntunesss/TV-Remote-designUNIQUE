#!/usr/bin/env bash
# Builds ComandoTV.apk with the plain Android SDK tools (no Gradle). Needs ANDROID_HOME with
# platforms;android-34 and build-tools;36.0.0, and a JDK 17.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:?set ANDROID_HOME}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-34/android.jar"
B=build
rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex"
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  --min-sdk-version 26 --target-sdk-version 34 "$B/res.zip"
javac -nowarn -Xlint:-options -encoding UTF-8 --release 11 -classpath "$JAR" -d "$B/classes" $(find src "$B/gen" -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
"$BT/apksigner" sign --ks remote.keystore --ks-pass pass:android --out "$B/ComandoTV.apk" "$B/aligned.apk"
echo "built $B/ComandoTV.apk ($(wc -c < "$B/ComandoTV.apk") bytes)"
