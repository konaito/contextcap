import React from "react";
import { AbsoluteFill, useCurrentFrame, useVideoConfig } from "remotion";
import { C, FONT, SNAP } from "../look";
import { enter, focusIn } from "./motion";

/// 黒地に 1 行だけの画面（Arc の 1 語の画面）。カットの句読点として 1〜2 拍だけ挟む
export const Word: React.FC<{ text: React.ReactNode; size?: number; sub?: string }> = ({ text, size = 96, sub }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = enter(frame, 0, fps, SNAP);
  return (
    <AbsoluteFill style={{ justifyContent: "center", alignItems: "center", background: C.bg }}>
      <div
        style={{
          fontFamily: FONT.ja,
          fontWeight: 400,
          fontSize: size,
          letterSpacing: "0.04em",
          color: C.text,
          transform: `scale(${0.96 + 0.04 * p})`,
          ...focusIn(p, 10),
        }}
      >
        {text}
      </div>
      {sub ? (
        <div
          style={{
            marginTop: 28,
            fontFamily: FONT.mono,
            fontSize: 20,
            letterSpacing: "0.22em",
            color: C.dim,
            ...focusIn(enter(frame, 4, fps, SNAP), 6),
          }}
        >
          {sub}
        </div>
      ) : null}
    </AbsoluteFill>
  );
};

/// 幕の左上に置く小さなラベル（短期／長期の区別を見せる）
export const ActLabel: React.FC<{ text: string }> = ({ text }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = enter(frame, 0, fps, SNAP);
  return (
    <div
      style={{
        position: "absolute",
        left: 120,
        top: 96,
        fontFamily: FONT.mono,
        fontSize: 20,
        letterSpacing: "0.22em",
        color: C.dim,
        ...focusIn(p, 6),
      }}
    >
      {text}
    </div>
  );
};
