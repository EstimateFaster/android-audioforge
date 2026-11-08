#!/usr/bin/env bash
set -euo pipefail

APK="${1:?usage: verify-apk.sh <apk>}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?ANDROID_SDK_ROOT/ANDROID_HOME not set}}"
BT="$SDK/build-tools/37.0.0"

test -f "$APK"

echo "== badging =="
"$BT/aapt2" dump badging "$APK" | sed -n '1,20p'

echo "== zipalign 16KB =="
"$BT/zipalign" -c -P 16 4 "$APK"

echo "== signature =="
"$BT/apksigner" verify --verbose "$APK"

echo "== native libs =="
unzip -l "$APK" | grep 'lib/' | awk '{print $4}' | cut -d/ -f2 | sort -u

echo "== size =="
du -h "$APK"
