#!/bin/bash
# swift build → ContextCap.app 生成 → /Applications へコピー
set -euo pipefail
cd "$(dirname "$0")/.."

swift build -c release

APP=build/ContextCap.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/ContextCap "$APP/Contents/MacOS/ContextCap"
cp Resources/Info.plist "$APP/Contents/Info.plist"

# アイコンは ../assets/icon.png（1024 マスター）から毎回生成する。
# .icns をリポジトリに置かないのは、マスターと二重管理になるのを避けるため。
# sips / iconutil は macOS 標準なので追加依存は増えない。
ICON_SRC=../assets/icon.png
ICONSET=build/AppIcon.iconset
rm -rf "$ICONSET"
mkdir -p "$ICONSET"
for spec in "16 icon_16x16" "32 icon_16x16@2x" "32 icon_32x32" "64 icon_32x32@2x" \
            "128 icon_128x128" "256 icon_128x128@2x" "256 icon_256x256" \
            "512 icon_256x256@2x" "512 icon_512x512" "1024 icon_512x512@2x"; do
  set -- $spec
  sips -z "$1" "$1" "$ICON_SRC" --out "$ICONSET/$2.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"
rm -rf "$ICONSET"

# 署名。キーチェーンに Developer ID Application の証明書があればそれで署名し、
# 無ければ ad-hoc にする（SIGN_IDENTITY で明示もできる。"-" で ad-hoc を強制）。
#
# Developer ID だと designated requirement が「identifier + チーム ID」になるので、
# 再ビルドしても画面収録・アクセシビリティの許可が外れない。
# ad-hoc の designated requirement は cdhash そのもので、ビルドのたびに一致しなくなる
# （identifier を固定しても効かない。TN3127）。
# Hardened Runtime（--options runtime）と secure timestamp は公証の前提。例外の entitlement は要らない
# （システムのフレームワークしかリンクしておらず、JIT もプラグインも無い）
IDENTITY="${SIGN_IDENTITY:-$(security find-identity -v -p codesigning \
  | sed -n 's/.*"\(Developer ID Application: [^"]*\)".*/\1/p' | head -1)}"
if [ -n "$IDENTITY" ] && [ "$IDENTITY" != "-" ]; then
  codesign --force --sign "$IDENTITY" --options runtime --timestamp \
    --identifier app.imichat.contextcap "$APP"
  echo "Signed: $IDENTITY"
else
  codesign --force --sign - --identifier app.imichat.contextcap "$APP"
  echo "Signed: ad-hoc（再ビルドのたびに画面収録の許可を取り直すことになる）"
fi

echo "Built: $APP"

if [ "${SKIP_INSTALL:-}" != "1" ]; then
  # 起動中なら止めてから差し替える
  pkill -x ContextCap 2>/dev/null || true
  rm -rf /Applications/ContextCap.app
  cp -R "$APP" /Applications/ContextCap.app
  echo "Installed: /Applications/ContextCap.app"
  echo "open /Applications/ContextCap.app で起動。初回は画面収録権限の許可が必要。"
fi
