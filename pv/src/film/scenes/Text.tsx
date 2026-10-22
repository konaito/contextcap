import React from "react";
import { AbsoluteFill, Easing, interpolate, useCurrentFrame, useVideoConfig } from "remotion";
import { C, FONT, SNAP } from "../look";
import { ERROR_SCREEN_LINES, FakeScreen } from "../parts/FakeScreen";
import { clamp, enter, focusIn, ramp } from "../parts/motion";
import { BEAT, FACTS } from "../timeline";

/// 文字にする。1 枚が手前に来て、光の線が上から下へなぞる。なぞった行から文字が剥がれて右に並ぶ。
/// 画像は消え、文字だけが残り、背後に溜まった文字の列が流れる
const ARCHIVE = [
  "Build complete! (2.62秒)",
  "検索結果  2 ページ目",
  "git commit -m \"fix: keep failed OCR images\"",
  "画像の期間: 3日と4時間",
  "OCR: 追いついている",
  "tests 104  failures 0",
  "PR #13 merged into main",
  "localhost:3000/search?q=contextcap",
  "読み取った文字を開く…",
  "ScreenCaptureKit  5 秒間隔",
  "useSearch.ts:17:9",
  "残り 15,906 件",
  "2026-08-03",
  "文字: 77 日分",
];

const CARD = { w: 980, h: 600, x: 110, y: 230 };

export const Text: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();

  const cardIn = enter(frame, 0, fps, SNAP);
  const scanStart = 8;
  const scanEnd = 8 + BEAT * 2;
  const scan = interpolate(frame, [scanStart, scanEnd], [0, 1], { ...clamp, easing: Easing.inOut(Easing.quad) });
  // 2 小節目の頭で画像が消え、文字だけが左へ寄る
  const cardOut = ramp(frame, BEAT * 4, BEAT * 5);
  const columnShift = interpolate(frame, [BEAT * 4, BEAT * 5 + 6], [0, 1], { ...clamp, easing: Easing.inOut(Easing.cubic) });

  const h1 = enter(frame, BEAT * 2, fps, SNAP);
  const h1Out = ramp(frame, BEAT * 4 - 4, BEAT * 4 + 2);
  const h2 = enter(frame, BEAT * 5, fps, SNAP);
  const facts = enter(frame, BEAT * 6, fps, SNAP);
  // 字数は 1 小節で一気に数え上がる
  const charsShown = Math.round(
    FACTS.chars * interpolate(frame, [BEAT * 6, BEAT * 10], [0, 1], { ...clamp, easing: Easing.out(Easing.cubic) }),
  );
  const archive = ramp(frame, BEAT * 4, BEAT * 6);
  const outFade = 1 - ramp(frame, durationInFrames - 6, durationInFrames);

  // 各行が走査線に触れるタイミング（行の縦位置から）
  const lineAt = (i: number) => scanStart + (scanEnd - scanStart) * (0.1 + i * 0.14);

  return (
    <AbsoluteFill style={{ opacity: outFade }}>
      {/* 背後で溜まっていく文字の列 */}
      <AbsoluteFill style={{ opacity: archive * 0.38, overflow: "hidden" }}>
        {Array.from({ length: 3 }, (_, col) => (
          <div
            key={col}
            style={{
              position: "absolute",
              left: 1110 + col * 270,
              width: 250,
              overflow: "hidden",
              textOverflow: "ellipsis",
              top: 0,
              transform: `translateY(${-((frame * (0.8 + col * 0.35)) % 600)}px)`,
              fontFamily: FONT.mono,
              fontSize: 17,
              lineHeight: 2.1,
              color: C.faint,
              whiteSpace: "nowrap",
            }}
          >
            {Array.from({ length: 60 }, (_, i) => (
              <div key={i}>{ARCHIVE[(i * 5 + col * 3) % ARCHIVE.length]}</div>
            ))}
          </div>
        ))}
      </AbsoluteFill>

      {/* 撮られた 1 枚 */}
      <AbsoluteFill style={{ perspective: 2000 }}>
        <div
          style={{
            position: "absolute",
            left: CARD.x,
            top: CARD.y,
            transform: `rotateY(${10 - 10 * cardIn}deg) scale(${0.86 + 0.14 * cardIn}) translateY(${cardOut * 40}px)`,
            opacity: cardIn * (1 - cardOut),
            filter: `blur(${(1 - cardIn) * 10 + cardOut * 14}px)`,
          }}
        >
          <FakeScreen kind="error" width={CARD.w} height={CARD.h} />
          {/* 走査線 */}
          <div
            style={{
              position: "absolute",
              left: -20,
              right: -20,
              top: 34 + scan * (CARD.h - 34),
              height: 2,
              background: C.signal,
              boxShadow: `0 0 24px 6px ${C.signal}66, 0 0 80px 20px ${C.signal}22`,
              opacity: frame > scanStart && frame < scanEnd + 6 ? 1 : 0,
            }}
          />
        </div>
      </AbsoluteFill>

      {/* 剥がれた文字 */}
      <div
        style={{
          position: "absolute",
          left: interpolate(columnShift, [0, 1], [1160, 120]),
          top: interpolate(columnShift, [0, 1], [300, 250]),
          width: 760,
          fontFamily: FONT.mono,
          fontSize: interpolate(columnShift, [0, 1], [24, 30]),
          lineHeight: 1.75,
          color: C.text,
        }}
      >
        {ERROR_SCREEN_LINES.map((line, i) => {
          const p = enter(frame, lineAt(i), fps, SNAP);
          return (
            <div
              key={i}
              style={{
                transform: `translateX(${(1 - p) * -40}px)`,
                color: i === 1 ? C.text : C.dim,
                ...focusIn(p, 8),
              }}
            >
              {line}
            </div>
          );
        })}
      </div>

      {/* 見出し */}
      <div
        style={{
          position: "absolute",
          left: 120,
          bottom: 110,
          fontFamily: FONT.ja,
          fontWeight: 400,
          fontSize: 64,
          letterSpacing: "0.04em",
          color: C.text,
        }}
      >
        <div style={{ position: "absolute", bottom: 0, whiteSpace: "nowrap", ...focusIn(h1 * (1 - h1Out), 10) }}>
          画像は、3日で消す。
        </div>
        <div style={{ position: "absolute", bottom: 0, whiteSpace: "nowrap", ...focusIn(h2, 10) }}>文字は、ずっと残す。</div>
      </div>

      {/* 事実。背後の文字の列の上に暗い面を敷いて読ませる */}
      <AbsoluteFill
        style={{
          background: "radial-gradient(ellipse 34% 30% at 84% 86%, rgba(10,10,10,0.95) 40%, transparent 100%)",
          opacity: facts,
        }}
      />
      <div
        style={{
          position: "absolute",
          right: 140,
          bottom: 110,
          textAlign: "right",
          fontFamily: FONT.mono,
          color: C.text,
          ...focusIn(facts, 10),
        }}
      >
        <div style={{ fontSize: 76, fontWeight: 300, fontVariantNumeric: "tabular-nums" }}>
          {charsShown.toLocaleString("en-US")}
        </div>
        <div style={{ marginTop: 4, fontSize: 20, letterSpacing: "0.14em", color: C.dim }}>
          CHARACTERS · {FACTS.days} DAYS · SINCE {FACTS.since}
        </div>
      </div>
    </AbsoluteFill>
  );
};
