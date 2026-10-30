import React from "react";
import { C, FONT } from "../look";

/// ターミナルの窓。中身は呼び出し側が組む。架空の画面で、実在の製品の見た目を写さない
export const Terminal: React.FC<{
  title: string;
  width?: number;
  height?: number;
  children: React.ReactNode;
}> = ({ title, width = 1240, height = 720, children }) => (
  <div
    style={{
      width,
      height,
      borderRadius: 18,
      background: `linear-gradient(180deg, #16171A 0%, ${C.panel} 100%)`,
      border: `1px solid ${C.line}`,
      boxShadow: "0 60px 120px rgba(0,0,0,0.6), inset 0 1px 0 rgba(255,255,255,0.05)",
      overflow: "hidden",
      display: "flex",
      flexDirection: "column",
    }}
  >
    <div
      style={{
        height: 52,
        display: "flex",
        alignItems: "center",
        gap: 10,
        padding: "0 22px",
        borderBottom: `1px solid ${C.line}`,
      }}
    >
      {[0, 1, 2].map((i) => (
        <div key={i} style={{ width: 12, height: 12, borderRadius: 6, background: "rgba(255,255,255,0.12)" }} />
      ))}
      <div style={{ marginLeft: 16, fontFamily: FONT.mono, fontSize: 18, color: C.faint }}>{title}</div>
    </div>
    <div style={{ flex: 1, padding: "34px 44px", position: "relative", overflow: "hidden" }}>{children}</div>
  </div>
);

/// 打鍵中のカーソル。15 フレームごとに点滅
export const Caret: React.FC<{ frame: number; color?: string }> = ({ frame, color = C.text }) => (
  <span
    style={{
      display: "inline-block",
      width: "0.55em",
      height: "1.05em",
      marginLeft: 4,
      verticalAlign: "-0.15em",
      background: color,
      opacity: Math.floor(frame / 15) % 2 === 0 ? 0.9 : 0,
    }}
  />
);
