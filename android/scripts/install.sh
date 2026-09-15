#!/usr/bin/env bash
# ContextCap Android をビルドして端末にインストールし、
# サイドロード時にブロックされるアクセシビリティ有効化まで解除する。
set -euo pipefail

cd "$(dirname "$0")/.."

PKG=app.imichat.contextcap

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Android 13+ はサイドロードしたアプリのアクセシビリティ有効化がブロックされる
adb shell appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow

cat <<'MSG'

インストール完了。

このあと設定アプリが開くので、以下を手で操作してください（adb だけでは完結しません。
settings put secure enabled_accessibility_services は Android 16 では反映されません）。

  1. Downloaded apps → ContextCap
  2. "Use ContextCap" をオン
  3. 確認ダイアログの "Allow" をタップ

有効化すると即座に撮影が始まります。

MSG

adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS
