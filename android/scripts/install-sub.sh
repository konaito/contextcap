#!/usr/bin/env bash
# contextcap-sub（決済ガード）をビルドして端末に入れ、必要な権限付与まで行う。
# これは俺用の companion アプリで、PayPay 等の前面時だけ ContextCap 本体の
# アクセシビリティを自動で OFF/ON する。
set -euo pipefail

cd "$(dirname "$0")/.."

PKG=app.imichat.contextcap.sub

./gradlew :sub:assembleDebug
adb install -r sub/build/outputs/apk/debug/sub-debug.apk

# アクセシビリティの ENABLED_ACCESSIBILITY_SERVICES を書き換えるのに必要。
# 通常アプリには付かないので adb で付与する（再起動後も保持される）。
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS

# サイドロードアプリの制限付き設定（使用状況アクセス）の解除。
adb shell appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow || true

cat <<'MSG'

インストールと WRITE_SECURE_SETTINGS の付与まで完了。

アプリ内で残りを手で操作してください。

  1. アプリを開く（ContextCap 決済ガード）
  2. 手順 2「使用状況アクセスの設定を開く」→ このアプリを許可
  3. 手順 3 で対象の決済アプリを確認（初期値は PayPay）。
     足りなければ、PayPay を一度開いてからアプリに戻り、
     「最後に検知した前面アプリ」に出た名前を対象リストに足す
  4. 手順 4「監視を開始」

これで決済アプリの前面時だけ ContextCap が自動で OFF になり、離れると ON に戻ります。

MSG

adb shell am start -n "$PKG/.MainActivity" || true
