import React from "react";
import { AbsoluteFill } from "remotion";
import { Appear } from "../components/Appear";
import { SceneFade } from "../components/SceneFade";
import { COLOR, ENTER_SLOW, FONT } from "../theme";
import { CLOSE_TAIL, CUES, FADE_OUT } from "../timeline";

/// 名前と、合言葉。最後は極端に遅く消えて、黒で終わる
export const Close: React.FC = () => (
  <SceneFade fadeOut={FADE_OUT.close} tail={CLOSE_TAIL}>
    <AbsoluteFill style={{ justifyContent: "center", alignItems: "center" }}>
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 36 }}>
        <Appear at={CUES.close[0]} drift={0}>
          <div
            style={{
              fontFamily: FONT.latin,
              fontWeight: 200,
              fontSize: 128,
              letterSpacing: "-0.02em",
              color: COLOR.text,
            }}
          >
            ContextCap
          </div>
        </Appear>
        <Appear at={CUES.close[1]} drift={6} config={ENTER_SLOW}>
          <div
            style={{
              fontFamily: FONT.ja,
              fontWeight: 300,
              fontSize: 30,
              letterSpacing: "0.1em",
              color: COLOR.muted,
            }}
          >
            コンテキストしか勝たん。
          </div>
        </Appear>
      </div>
    </AbsoluteFill>
  </SceneFade>
);
