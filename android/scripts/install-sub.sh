#!/usr/bin/env bash
# contextcap-sub（決済ガード）をビルドして端末に入れ、必要な権限付与まで行う。
# これは俺用の companion アプリで、PayPay 等の前面時だけ ContextCap 本体の
# アクセシビリティを自動で OFF/ON する。
set -euo pipefail

cd "$(dirname "$0")/.."

PKG=app.imichat.contextcap.sub

./gradlew :sub:assembleDebug

# 署名は :app と同じ固定の鍵（~/.android-keystores/contextcap-debug.keystore）でそろえている。
# それでも端末の版と鍵が違って上書きできないときは、ここで止める。adb uninstall には進まない
# （sub 自体はデータを持たないが、挙動を :app の install.sh とそろえ、署名の食い違いに気づけるようにする）。
if ! out=$(adb install -r sub/build/outputs/apk/debug/sub-debug.apk 2>&1); then
  echo "$out"
  if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE <<<"$out"; then
    cat <<'MSG'

署名の鍵が、端末に入っている版と違う。adb uninstall はせず、まず
~/.android-keystores/contextcap-debug.keystore が端末の版の鍵か確かめる
（keytool -list -v -keystore <鍵> -storepass android の SHA-256 が一致するか）。
MSG
  fi
  exit 1
fi
echo "$out" | tail -1

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
