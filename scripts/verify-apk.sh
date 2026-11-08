#!/usr/bin/env bash
# Verify a built AudioForge APK: signature, package/version identity, ABI set, 16 KB alignment.
#
# Usage:
#   scripts/verify-apk.sh <apk> [--package <applicationId>] [--version-code <n>]
#                              [--version-name <x.y.z[-suffix]>] [--abi <abi>]...
#
# Defaults (used by CI for the debug APK):
#   package      com.estimatefaster.audioforge (or its .debug variant)
#   versionCode  any positive integer
#   versionName  X.Y.Z or X.Y.Z-<suffix>
#   ABIs         arm64-v8a + x86_64 (universal APK; both must be present)
#
# Environment:
#   ANDROID_SDK_ROOT / ANDROID_HOME  Android SDK root (required)
#   BUILD_TOOLS_DIR                  default: <sdk>/build-tools/37.0.0
#   ALLOWED_ABIS                     default: "arm64-v8a x86_64"
set -euo pipefail

usage() {
  cat <<'EOF'
usage: verify-apk.sh <apk> [options]

Options:
  --package <id>          exact applicationId (release: com.estimatefaster.audioforge)
  --version-code <n>      exact versionCode
  --version-name <name>   exact versionName (X.Y.Z or X.Y.Z-suffix)
  --abi <abi>             required ABI; repeatable (default: arm64-v8a x86_64)
  -h, --help              show this help

Checks: apksigner signature, aapt2 package/versionCode/versionName, ABI set,
        zipalign 16 KB, size. Exits non-zero on the first failure.

Environment:
  ANDROID_SDK_ROOT / ANDROID_HOME  Android SDK root (required)
  BUILD_TOOLS_DIR                  default: <sdk>/build-tools/37.0.0
  ALLOWED_ABIS                     default: "arm64-v8a x86_64"
EOF
}

die() {
  printf 'verify-apk: ERROR: %s\n' "$*" >&2
  exit 1
}

APK=""
EXPECT_PACKAGE=""
EXPECT_VERSION_CODE=""
EXPECT_VERSION_NAME=""
EXPECT_ABIS=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --package)
      { [ "$#" -ge 2 ] && [ -n "$2" ]; } || die "--package 缺少参数"
      EXPECT_PACKAGE="$2"
      shift 2
      ;;
    --version-code)
      { [ "$#" -ge 2 ] && [ -n "$2" ]; } || die "--version-code 缺少参数"
      EXPECT_VERSION_CODE="$2"
      shift 2
      ;;
    --version-name)
      { [ "$#" -ge 2 ] && [ -n "$2" ]; } || die "--version-name 缺少参数"
      EXPECT_VERSION_NAME="$2"
      shift 2
      ;;
    --abi)
      { [ "$#" -ge 2 ] && [ -n "$2" ]; } || die "--abi 缺少参数"
      EXPECT_ABIS+=("$2")
      shift 2
      ;;
    -*)
      die "未知参数: $1（--help 查看用法）"
      ;;
    *)
      [ -z "$APK" ] || die "只能校验一个 APK（多余参数: $1）"
      APK="$1"
      shift
      ;;
  esac
done

if [ -z "$APK" ]; then
  usage >&2
  exit 2
fi

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[ -n "$SDK" ] || die "未设置 ANDROID_SDK_ROOT 或 ANDROID_HOME；请安装 Android SDK 并导出该变量"

BT="${BUILD_TOOLS_DIR:-$SDK/build-tools/37.0.0}"
[ -d "$BT" ] || die "找不到 build-tools 37.0.0: $BT（请安装 'build-tools;37.0.0'，或用 BUILD_TOOLS_DIR 指定）"
for tool in aapt2 apksigner zipalign; do
  [ -x "$BT/$tool" ] || die "缺少工具: $BT/$tool（请安装 'build-tools;37.0.0'）"
done
command -v unzip >/dev/null 2>&1 || die "缺少 unzip 命令（用于读取 APK 内的 lib/<abi>/）"

[ -f "$APK" ] || die "APK 不存在: $APK"
[ -s "$APK" ] || die "APK 为空文件: $APK"

echo "== badging =="
BADGING="$("$BT/aapt2" dump badging "$APK")" \
  || die "aapt2 dump badging 失败（APK 损坏或不是有效 APK）: $APK"
printf '%s\n' "$BADGING" | sed -n '1,20p'

PKG_LINE="$(printf '%s\n' "$BADGING" | grep -m1 '^package: ' || true)"
[ -n "$PKG_LINE" ] || die "aapt2 输出中没有 package 行: $APK"
PKG="$(printf '%s\n' "$PKG_LINE" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
PKG_CODE="$(printf '%s\n' "$PKG_LINE" | sed -n "s/.* versionCode='\([^']*\)'.*/\1/p")"
PKG_NAME="$(printf '%s\n' "$PKG_LINE" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")"
[ -n "$PKG" ] || die "无法从 badging 解析包名: $APK"
[ -n "$PKG_CODE" ] || die "无法从 badging 解析 versionCode（release/debug 构建必须显式设置）: $APK"
[ -n "$PKG_NAME" ] || die "无法从 badging 解析 versionName: $APK"

echo
echo "package:      $PKG"
echo "versionCode:  $PKG_CODE"
echo "versionName:  $PKG_NAME"

if [ -n "$EXPECT_PACKAGE" ]; then
  [ "$PKG" = "$EXPECT_PACKAGE" ] \
    || die "包名不匹配: 期望 '$EXPECT_PACKAGE'，实际 '$PKG'"
else
  if [ "$PKG" != "com.estimatefaster.audioforge" ] \
    && [ "$PKG" != "com.estimatefaster.audioforge.debug" ]; then
    die "包名异常: '$PKG'（期望 com.estimatefaster.audioforge 或 .debug；其他变体请用 --package 指定）"
  fi
fi

printf '%s\n' "$PKG_CODE" | grep -Eq '^[1-9][0-9]*$' \
  || die "versionCode 非法: '$PKG_CODE'（应为正整数）"
if [ -n "$EXPECT_VERSION_CODE" ]; then
  [ "$PKG_CODE" = "$EXPECT_VERSION_CODE" ] \
    || die "versionCode 不匹配: 期望 '$EXPECT_VERSION_CODE'，实际 '$PKG_CODE'"
fi

VERSION_RE='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$'
printf '%s\n' "$PKG_NAME" | grep -Eq "$VERSION_RE" \
  || die "versionName 非法: '$PKG_NAME'（期望 X.Y.Z 或 X.Y.Z-suffix）"
if [ -n "$EXPECT_VERSION_NAME" ]; then
  [ "$PKG_NAME" = "$EXPECT_VERSION_NAME" ] \
    || die "versionName 不匹配: 期望 '$EXPECT_VERSION_NAME'，实际 '$PKG_NAME'"
fi

echo
echo "== native libs (ABI) =="
unzip -Z1 "$APK" >/dev/null 2>&1 || die "unzip 无法读取 APK: $APK"
ABI_LIST="$(unzip -Z1 "$APK" | sed -n 's#^lib/\([^/]*\)/[^/]*\.so$#\1#p' | sort -u)"
[ -n "$ABI_LIST" ] || die "APK 不含任何 lib/<abi>/*.so；FFmpegKit 原生库缺失？"
printf '%s\n' "$ABI_LIST" | sed 's/^/  /'

ALLOWED="${ALLOWED_ABIS:-arm64-v8a x86_64}"
for abi in $ABI_LIST; do
  case " $ALLOWED " in
    *" $abi "*) ;;
    *) die "APK 含未声明的 ABI: '$abi'（允许: $ALLOWED）" ;;
  esac
done

if [ "${#EXPECT_ABIS[@]}" -eq 0 ]; then
  EXPECT_ABIS=(arm64-v8a x86_64)
fi
for abi in "${EXPECT_ABIS[@]}"; do
  if ! printf '%s\n' "$ABI_LIST" | grep -qx "$abi"; then
    ACTUAL_ABIS="$(printf '%s' "$ABI_LIST" | tr '\n' ' ')"
    die "APK 缺少必需 ABI: '$abi'（实际: ${ACTUAL_ABIS% }）"
  fi
done

echo
echo "== signature =="
"$BT/apksigner" verify --verbose --print-certs "$APK" \
  || die "apksigner verify 失败：APK 未签名、签名损坏或签名方案不兼容（release 必须由 CI 用 ANDROID_KEYSTORE_* 签名）"

echo
echo "== zipalign 16 KB =="
"$BT/zipalign" -c -P 16 4 "$APK" \
  || die "zipalign 校验失败：APK 未按 16 KB 对齐（build-tools 37 的 zipalign -P 16 检查）"

echo
echo "== size =="
du -h "$APK"

echo
echo "verify-apk: OK ($APK)"
