import React from "react";
import { AbsoluteFill } from "remotion";
import { Appear } from "../components/Appear";
import { SceneFade } from "../components/SceneFade";
import { COLOR, ENTER_SLOW, FONT } from "../theme";
import { CUES, FADE_OUT } from "../timeline";

/// 中央に大きく 1 行、下に小さく 1 行。ほぼ静止
export const LongTerm: React.FC = () => (
  <SceneFade fadeOut={FADE_OUT.longTerm}>
    <AbsoluteFill style={{ justifyContent: "center", alignItems: "center" }}>
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 48 }}>
        <Appear at={CUES.longTerm[0]} drift={8}>
          <div
            style={{
              fontFamily: FONT.ja,
              fontWeight: 300,
              fontSize: 84,
              letterSpacing: "0.05em",
              color: COLOR.text,
            }}
          >
            未来のAIに、自分を知らせる。
          </div>
        </Appear>
        <Appear at={CUES.longTerm[1]} drift={6} config={ENTER_SLOW}>
          <div
            style={{
              fontFamily: FONT.ja,
              fontWeight: 300,
              fontSize: 32,
              letterSpacing: "0.08em",
              color: COLOR.muted,
            }}
          >
            今、残したものが、<span style={{ color: COLOR.accent }}>シグナル</span>になる。
          </div>
        </Appear>
      </div>
    </AbsoluteFill>
  </SceneFade>
);
