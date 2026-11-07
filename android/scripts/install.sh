#!/usr/bin/env bash
# ContextCap Android をビルドして端末にインストールし、
# サイドロード時にブロックされるアクセシビリティ有効化まで解除する。
set -euo pipefail

cd "$(dirname "$0")/.."

PKG=app.imichat.contextcap

./gradlew assembleDebug

# 署名が端末の版と違うと上書きできない。ここでアンインストールに進むと
# 端末内の撮影データ（Android/data/<pkg>/files）が全部消えるので、止めて手順を示す。
if ! out=$(adb install -r app/build/outputs/apk/debug/app-debug.apk 2>&1); then
  echo "$out"
  if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE <<<"$out"; then
    cat <<'MSG'

署名の鍵が、端末に入っている版と違う。adb uninstall はしないこと。端末内の撮影データが消える。
まず ~/.android-keystores/contextcap-debug.keystore が端末の版の鍵か確かめる。
入れ直すしかないときは、android/README.md の「署名の鍵が変わったとき」の手順で
データを退避してから進める。
MSG
  fi
  exit 1
fi
echo "$out" | tail -1

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
