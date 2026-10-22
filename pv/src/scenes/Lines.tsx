import React from "react";
import { AbsoluteFill } from "remotion";
import { Appear } from "../components/Appear";
import { SceneFade } from "../components/SceneFade";
import { COLOR, FONT, MARGIN_X } from "../theme";

/// 左揃えで、間を空けて 1 行ずつ出す。Scene 2 と Scene 4 で使う
export const Lines: React.FC<{
  lines: readonly React.ReactNode[];
  cues: readonly number[];
  fadeOut: number;
  fontSize: number;
  gap: number;
}> = ({ lines, cues, fadeOut, fontSize, gap }) => (
  <SceneFade fadeOut={fadeOut}>
    <AbsoluteFill style={{ justifyContent: "center", paddingLeft: MARGIN_X }}>
      <div style={{ display: "flex", flexDirection: "column", gap }}>
        {lines.map((line, i) => (
          <Appear key={i} at={cues[i]} drift={12}>
            <div
              style={{
                fontFamily: FONT.ja,
                fontWeight: 300,
                fontSize,
                letterSpacing: "0.05em",
                color: COLOR.text,
              }}
            >
              {line}
            </div>
          </Appear>
        ))}
      </div>
    </AbsoluteFill>
  </SceneFade>
);
