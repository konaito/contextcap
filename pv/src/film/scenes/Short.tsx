import React from "react";
import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { C, FONT, SNAP } from "../look";
import { clamp, enter, focusIn, ramp } from "../parts/motion";
import { Caret, Terminal } from "../parts/Terminal";
import { ActLabel } from "../parts/Word";
import { BEAT, FOUND_LINE, LONG_EXPLANATION, SHORT_PROMPT } from "../timeline";

/// 短期。最初と同じターミナルに、今度は短く打つだけ。手元の文字から、さっき見ていたエラーの行が見つかる。
/// 最後に、最初の長い説明が 1 行のピルに潰れる（Dia の収束）
export const Short: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();

  const typed = Math.max(0, Math.floor((frame - 4) * 1.1));
  const prompt = SHORT_PROMPT.slice(0, typed);

  const appear = enter(frame, 0, fps, SNAP);
  const l1 = enter(frame, BEAT * 1, fps, SNAP);
  const found = enter(frame, BEAT * 2, fps, SNAP);
  const highlight = ramp(frame, BEAT * 2 + 4, BEAT * 3);
  const l2 = enter(frame, BEAT * 3, fps, SNAP);

  // 2 小節目の頭: 長い説明が立ち上がり、1 拍で 1 行に潰れる
  const wallAt = BEAT * 5;
  const wallIn = enter(frame, wallAt, fps, SNAP);
  const collapse = spring({ frame: frame - (wallAt + BEAT), fps, config: { damping: 22, stiffness: 160, mass: 0.8 } });
  const terminalDim = ramp(frame, wallAt - 4, wallAt + 6);
  const sweep = interpolate(frame, [wallAt + BEAT + 8, wallAt + BEAT * 2 + 8], [-40, 140], clamp);
  const headline = enter(frame, wallAt + BEAT * 2, fps, SNAP);
  const outFade = 1 - ramp(frame, durationInFrames - 6, durationInFrames);

  const wallH = interpolate(collapse, [0, 1], [520, 76]);
  const wallW = interpolate(collapse, [0, 1], [1180, 560]);

  return (
    <AbsoluteFill style={{ opacity: outFade }}>
      <AbsoluteFill style={{ justifyContent: "center", alignItems: "center", perspective: 2000 }}>
        <div
          style={{
            transform: `rotateX(${8 - 4 * appear}deg) scale(${0.94 + 0.06 * appear + 0.03 * ramp(frame, 0, durationInFrames)})`,
            opacity: appear * (1 - terminalDim * 0.82),
            filter: `blur(${terminalDim * 6}px)`,
          }}
        >
          <Terminal title="~/projects/search — claude">
            <div style={{ fontFamily: FONT.mono, fontSize: 34, lineHeight: 1.7, color: C.text }}>
              <div>
                <span style={{ color: C.faint }}>{"> "}</span>
                {prompt}
                {frame < BEAT ? <Caret frame={frame} /> : null}
              </div>
              <div style={{ marginTop: 26, fontSize: 24, color: C.dim, ...focusIn(l1, 6) }}>
                ● ~/ContextCap-analysis/ocr.sqlite を読む　直近 10 分
              </div>
              <div
                style={{
                  marginTop: 20,
                  padding: "18px 22px",
                  borderRadius: 12,
                  background: `rgba(143,163,184,${0.04 + 0.08 * highlight})`,
                  borderLeft: `3px solid rgba(143,163,184,${0.3 + 0.7 * highlight})`,
                  transform: `translateY(${(1 - found) * 16}px)`,
                  ...focusIn(found, 8),
                }}
              >
                <div style={{ fontSize: 18, letterSpacing: "0.08em", color: C.dim }}>
                  3分前 · Chrome · localhost:3000/search?page=2
                </div>
                <div style={{ marginTop: 8, fontSize: 24, color: C.text }}>{FOUND_LINE}</div>
              </div>
              <div style={{ marginTop: 22, fontSize: 26, color: C.text, ...focusIn(l2, 6) }}>
                ● さっき見ていたエラーです。useSearch の初期値を直します。
              </div>
            </div>
          </Terminal>
        </div>
      </AbsoluteFill>

      <AbsoluteFill style={{ justifyContent: "center", alignItems: "center" }}>
        <div
          style={{
            width: wallW,
            height: wallH,
            borderRadius: interpolate(collapse, [0, 1], [18, 38]),
            border: `1px solid rgba(255,255,255,${0.1 * wallIn})`,
            background: "rgba(17,18,20,0.94)",
            overflow: "hidden",
            position: "relative",
            opacity: wallIn,
            transform: `translateY(${-60 * collapse}px)`,
          }}
        >
          <div
            style={{
              position: "absolute",
              inset: "28px 36px",
              fontFamily: FONT.mono,
              fontSize: 28,
              lineHeight: 1.75,
              color: C.dim,
              opacity: 1 - collapse,
              WebkitMaskImage: "linear-gradient(180deg, #000 60%, transparent 100%)",
              maskImage: "linear-gradient(180deg, #000 60%, transparent 100%)",
            }}
          >
            {"> "}
            {LONG_EXPLANATION}
          </div>
          <div
            style={{
              position: "absolute",
              inset: 0,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontFamily: FONT.mono,
              fontSize: 30,
              color: C.text,
              opacity: ramp(frame, wallAt + BEAT + 4, wallAt + BEAT + 12),
            }}
          >
            {"> "}
            {SHORT_PROMPT}
          </div>
          <div
            style={{
              position: "absolute",
              top: 0,
              bottom: 0,
              left: `${sweep}%`,
              width: "38%",
              background: `linear-gradient(90deg, transparent, ${C.signal}55, transparent)`,
              opacity: collapse > 0.9 ? 1 : 0,
            }}
          />
        </div>
        <div
          style={{
            position: "absolute",
            top: 620,
            fontFamily: FONT.ja,
            fontWeight: 400,
            fontSize: 68,
            letterSpacing: "0.04em",
            color: C.text,
            ...focusIn(headline, 12),
          }}
        >
          説明が、一個減る。
        </div>
      </AbsoluteFill>
      <ActLabel text="NOW · 直近の意図を汲む" />
    </AbsoluteFill>
  );
};
