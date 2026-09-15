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

# ad-hoc 署名（identifier を固定して TCC 権限が剥がれにくいようにする）
codesign --force --sign - --identifier app.imichat.contextcap "$APP"

echo "Built: $APP"

if [ "${SKIP_INSTALL:-}" != "1" ]; then
  # 起動中なら止めてから差し替える
  pkill -x ContextCap 2>/dev/null || true
  rm -rf /Applications/ContextCap.app
  cp -R "$APP" /Applications/ContextCap.app
  echo "Installed: /Applications/ContextCap.app"
  echo "open /Applications/ContextCap.app で起動。初回は画面収録権限の許可が必要。"
fi
