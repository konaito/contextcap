import React from "react";
import { AbsoluteFill, Easing, interpolate, Sequence, useCurrentFrame, useVideoConfig } from "remotion";
import { C, FONT, SNAP } from "../look";
import { clamp, enter, focusIn, ramp } from "../parts/motion";
import { ActLabel } from "../parts/Word";
import { BEAT, FACTS } from "../timeline";

/// 長期。前半は 1 日 1 マスの格子が、記録の始まり（8/3）から今日（10/22）まで埋まり、
/// そのまま「このペースで」1 年先まで点線のマスで伸びる。字数も実測から見込みへ数え上がる。
/// 後半は文字の海から引いていくと、人の輪郭が浮かぶ（未来の AI が、あなたを知るための記録）
const REAL_DAYS = 81; // 2026-08-03〜2026-10-22（暦日。撮れた日は 77 日）
const YEAR_DAYS = 365;
const START = new Date(Date.UTC(2026, 7, 3));

const dateOf = (day: number) => {
  const d = new Date(START.getTime() + day * 86_400_000);
  return `${d.getUTCFullYear()}.${String(d.getUTCMonth() + 1).padStart(2, "0")}.${String(d.getUTCDate()).padStart(2, "0")}`;
};

const ROWS = 7;
const CELL = 17;
const GAP = 5;

const Calendar: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  // 実測の 81 日を 2 小節、見込みの残りを 2 小節で埋める
  const day = Math.floor(
    frame < BEAT * 8
      ? interpolate(frame, [4, BEAT * 8], [0, REAL_DAYS], { ...clamp, easing: Easing.inOut(Easing.cubic) })
      : interpolate(frame, [BEAT * 8, BEAT * 16], [REAL_DAYS, YEAR_DAYS], { ...clamp, easing: Easing.inOut(Easing.cubic) }),
  );
  const projected = day > REAL_DAYS;
  const chars = Math.round(
    frame < BEAT * 8
      ? interpolate(frame, [4, BEAT * 8], [0, FACTS.chars], clamp)
      : interpolate(frame, [BEAT * 8, BEAT * 16], [FACTS.chars, FACTS.charsPerYear], clamp),
  );
  const cols = Math.ceil(YEAR_DAYS / ROWS);
  const copy1 = enter(frame, BEAT, fps, SNAP);
  const copy1Out = ramp(frame, BEAT * 8 - 6, BEAT * 8);
  const copy2 = enter(frame, BEAT * 8, fps, SNAP);
  return (
    <AbsoluteFill>
      <div
        style={{
          position: "absolute",
          left: (1920 - cols * (CELL + GAP)) / 2,
          top: 330,
          display: "grid",
          gridTemplateRows: `repeat(${ROWS}, ${CELL}px)`,
          gridAutoFlow: "column",
          gridAutoColumns: `${CELL}px`,
          gap: GAP,
        }}
      >
        {Array.from({ length: YEAR_DAYS }, (_, i) => {
          const filled = i < day;
          const real = i < REAL_DAYS;
          return (
            <div
              key={i}
              style={{
                width: CELL,
                height: CELL,
                borderRadius: 4,
                background: filled && real ? `rgba(237,237,237,${0.35 + 0.5 * ((i * 37) % 11) / 10})` : "transparent",
                border: filled && !real ? `1px dashed ${C.signal}` : filled ? "none" : `1px solid ${C.line}`,
                opacity: filled ? 1 : 0.6,
              }}
            />
          );
        })}
      </div>
      <div style={{ position: "absolute", left: 160, top: 560, fontFamily: FONT.mono, color: C.text }}>
        <div style={{ fontSize: 88, fontWeight: 300, fontVariantNumeric: "tabular-nums", color: projected ? C.signal : C.text }}>
          {dateOf(Math.max(0, day - 1))}
        </div>
        <div style={{ marginTop: 6, fontSize: 20, letterSpacing: "0.16em", color: C.dim }}>
          {projected ? "このペースで続けたら（見込み）" : `記録の始まり ${FACTS.since} から`}
        </div>
      </div>
      <div style={{ position: "absolute", right: 160, top: 560, textAlign: "right", fontFamily: FONT.mono }}>
        <div style={{ fontSize: 88, fontWeight: 300, fontVariantNumeric: "tabular-nums", color: projected ? C.signal : C.text }}>
          {chars.toLocaleString("en-US")}
        </div>
        <div style={{ marginTop: 6, fontSize: 20, letterSpacing: "0.16em", color: C.dim }}>CHARACTERS</div>
      </div>
      <div
        style={{
          position: "absolute",
          left: 160,
          bottom: 110,
          fontFamily: FONT.ja,
          fontWeight: 400,
          fontSize: 64,
          letterSpacing: "0.04em",
          color: C.text,
        }}
      >
        <div style={{ position: "absolute", bottom: 0, whiteSpace: "nowrap", ...focusIn(copy1 * (1 - copy1Out), 10) }}>
          これは、今日のためだけじゃない。
        </div>
        <div style={{ position: "absolute", bottom: 0, whiteSpace: "nowrap", ...focusIn(copy2, 10) }}>
          1年分の画面は、1年分の文脈になる。
        </div>
      </div>
    </AbsoluteFill>
  );
};

const LINES = [
  "Build complete! (2.62秒)", "検索結果 2 ページ目", "git commit -m \"fix: keep failed OCR images\"",
  "画像の期間: 3日と4時間", "OCR: 追いついている", "tests 104 failures 0", "PR #13 merged into main",
  "localhost:3000/search?q=contextcap", "読み取った文字を開く…", "TypeError: Cannot read properties",
  "useSearch.ts:17:9", "残り 15,906 件", "2026-08-03", "文字: 77 日分", "コンテキストしか勝たん。",
  "さっきのエラー、直して", "5秒に1枚", "手元に残す", "画面は、全部見てた。",
];

/// 人の輪郭（頭と肩）。1920×1080 の座標で描き、マスクに使う
const SILHOUETTE =
  "data:image/svg+xml;utf8," +
  encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1920 1080"><circle cx="960" cy="390" r="150" fill="white"/>` +
      `<path d="M600 1080 C610 790 760 650 960 650 C1160 650 1310 790 1320 1080 Z" fill="white"/></svg>`,
  );

const TextField: React.FC<{ bright: boolean }> = ({ bright }) => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{ overflow: "hidden" }}>
      {Array.from({ length: 64 }, (_, row) => {
        const dir = row % 2 === 0 ? -1 : 1;
        const speed = 0.6 + ((row * 13) % 7) * 0.25;
        const text = Array.from({ length: 12 }, (_, k) => LINES[(row * 3 + k * 5) % LINES.length]).join("    ");
        return (
          <div
            key={row}
            style={{
              position: "absolute",
              top: row * 17,
              left: -400 + dir * ((frame * speed) % 400),
              whiteSpace: "nowrap",
              fontFamily: FONT.mono,
              fontSize: 12,
              lineHeight: "17px",
              color: bright ? `rgba(237,237,237,${0.55 + 0.35 * ((row * 7) % 5) / 4})` : "rgba(237,237,237,0.07)",
            }}
          >
            {text}
          </div>
        );
      })}
    </AbsoluteFill>
  );
};

const Portrait: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();
  // 顔の中に寄ったところ（文字だけ）から、引いて輪郭を見せる
  const zoom = interpolate(frame, [0, BEAT * 10], [3.4, 1], { ...clamp, easing: Easing.inOut(Easing.cubic) });
  const reveal = ramp(frame, BEAT * 2, BEAT * 8);
  const pulse = 0.5 + 0.5 * Math.cos(((frame % BEAT) / BEAT) * Math.PI * 2);
  const copy1 = enter(frame, BEAT * 6, fps, SNAP);
  const copy1Out = ramp(frame, BEAT * 11 - 6, BEAT * 11);
  const copy2 = enter(frame, BEAT * 11, fps, SNAP);
  const out = 1 - ramp(frame, durationInFrames - 8, durationInFrames);
  return (
    <AbsoluteFill style={{ opacity: out }}>
      <AbsoluteFill style={{ transform: `scale(${zoom})`, transformOrigin: "960px 400px" }}>
        <TextField bright={false} />
        <AbsoluteFill
          style={{
            WebkitMaskImage: `url("${SILHOUETTE}")`,
            maskImage: `url("${SILHOUETTE}")`,
            WebkitMaskSize: "100% 100%",
            maskSize: "100% 100%",
            opacity: 0.25 + 0.75 * reveal,
          }}
        >
          <TextField bright />
          <AbsoluteFill style={{ background: `radial-gradient(ellipse 30% 40% at 50% 45%, ${C.signal}${Math.round(20 + 18 * pulse).toString(16)} 0%, transparent 70%)` }} />
        </AbsoluteFill>
      </AbsoluteFill>
      <AbsoluteFill style={{ background: "linear-gradient(180deg, transparent 62%, rgba(5,5,5,0.9) 86%)" }} />
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          bottom: 96,
          textAlign: "center",
          fontFamily: FONT.ja,
          fontWeight: 400,
          fontSize: 64,
          letterSpacing: "0.04em",
          color: C.text,
        }}
      >
        <div style={{ position: "absolute", left: 0, right: 0, bottom: 0, ...focusIn(copy1 * (1 - copy1Out), 10) }}>
          未来のAIが、あなたを知るための記録。
        </div>
        <div style={{ position: "absolute", left: 0, right: 0, bottom: 0, ...focusIn(copy2, 10) }}>文脈は、溜めた人のもの。</div>
      </div>
    </AbsoluteFill>
  );
};

export const Long: React.FC = () => (
  <AbsoluteFill>
    <Sequence from={0} durationInFrames={BEAT * 16} layout="none">
      <AbsoluteFill>
        <Calendar />
      </AbsoluteFill>
    </Sequence>
    <Sequence from={BEAT * 16} durationInFrames={BEAT * 16} layout="none">
      <AbsoluteFill>
        <Portrait />
      </AbsoluteFill>
    </Sequence>
    <ActLabel text="LATER · 未来のAIへ" />
  </AbsoluteFill>
);
