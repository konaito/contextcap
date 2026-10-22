#!/usr/bin/env bash
# macOS 側にはテストターゲットが無い（Package.swift は executable のみ）ので、
# 検証したいソースを直接コンパイルして叩く。`swift test` は使えない。
#
#   ./scripts/run-tests.sh
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=Sources/ContextCap
BIN=$(mktemp -d)/retention-test
swiftc -O -parse-as-library "$SRC/Retention.swift" "$SRC/CaptureFile.swift" \
  scripts/retention-test.swift -o "$BIN"
"$BIN"

BIN2=$(mktemp -d)/ocr-streak-test
swiftc -O -parse-as-library "$SRC/OCRFailureStreak.swift" scripts/ocr-streak-test.swift -o "$BIN2"
"$BIN2"

BIN3=$(mktemp -d)/ocr-store-test
swiftc -O -parse-as-library "$SRC/OCRStore.swift" scripts/ocr-store-test.swift -o "$BIN3"
"$BIN3"
