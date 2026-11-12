#!/usr/bin/env bash
# 在模拟器里逐屏启动 :uipreview 并截图，产物用于设计验收。
set -euo pipefail

PKG="com.estimatefaster.audioforge.uipreview.debug"
ACT="$PKG/com.estimatefaster.audioforge.uipreview.MainActivity"
APK_DIR="${APK_DIR:-uipreview-apk}"
OUT="${OUT:-shots}"

mkdir -p "$OUT"
adb install -r -t "$APK_DIR/AudioForge-UI-Preview.apk" >/dev/null
adb shell settings put global animator_duration_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global window_animation_scale 0
adb shell wm size 1080x2340 >/dev/null 2>&1 || true
adb shell wm density 420 >/dev/null 2>&1 || true
sleep 2

capture() {
  local screen="$1" theme="$2" font="$3" phase="$4" name="$5"
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  adb logcat -c || true
  adb shell am start -W -n "$ACT" \
    --es screen "$screen" \
    --es theme "$theme" \
    --es font "$font" \
    --es phase "$phase" \
    --ez dynamic false >/dev/null
  sleep 6
  adb exec-out screencap -p > "$OUT/$name.png"
  echo "captured $name"
}

capture library  light 100 normal library-light
capture library  dark  100 normal library-dark
capture library  light 200 normal library-font200
capture library  light 100 empty  library-empty
capture project  light 100 normal project-light
capture project  dark  100 normal project-dark
capture project  light 100 error  project-error
capture chapters light 100 normal chapters-light
capture script   light 100 normal script-light
capture script   dark  100 normal script-dark
capture player   light 100 normal player-light
capture player   dark  100 normal player-dark
capture settings light 100 normal settings-light
capture settings dark  100 normal settings-dark
capture lab      light 100 normal lab-light
capture characters light 100 normal characters-light
capture tasks    light 100 normal tasks-light
capture storage  light 100 normal storage-light
capture about    light 100 normal about-light
capture model    light 100 normal model-light
capture onboarding light 100 normal onboarding-light

ls -lh "$OUT"
