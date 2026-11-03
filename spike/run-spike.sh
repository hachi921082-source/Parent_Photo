#!/bin/bash
# 使い方: bash spike/run-spike.sh <adb serial> <添付枚数 1|2>
# R8 release APK(ビルド済み)を入れて起動し、Logcat(SpikeResult)の結果行を表示する。
ADB=${ADB:-$(cygpath -u "$LOCALAPPDATA")/Android/Sdk/platform-tools/adb.exe}
SERIAL=$1
N=${2:-1}
APK=$(cygpath -w "$(dirname "$0")/app/build/outputs/apk/release/app-release.apk")
"$ADB" -s "$SERIAL" install -r "$APK" 2>&1 | tail -1
"$ADB" -s "$SERIAL" shell am force-stop com.example.parentphoto.spike
"$ADB" -s "$SERIAL" logcat -c
"$ADB" -s "$SERIAL" shell am start -n com.example.parentphoto.spike/.MainActivity --ei autosend "$N" >/dev/null
for i in $(seq 1 40); do
  sleep 2
  "$ADB" -s "$SERIAL" logcat -d -s SpikeResult:I | grep -q "RESULT" && break
done
"$ADB" -s "$SERIAL" logcat -d -s SpikeResult:I AndroidRuntime:E | grep -v "^---"
