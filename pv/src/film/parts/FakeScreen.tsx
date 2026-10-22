import React from "react";
import { C, FONT } from "../look";

/// 撮影された画面のつもりの、架空の画面。実データは映さない。
/// 種類は番号から決める（ブラウザのエラー / エディタ / チャット / 文書）
const KINDS = ["error", "code", "chat", "doc"] as const;
export type ScreenKind = (typeof KINDS)[number];
export const kindOf = (i: number): ScreenKind => KINDS[(i * 7 + 3) % KINDS.length];

/// OCR で読み取られる行。text 場面でもこの並びを使う
export const ERROR_SCREEN_LINES = [
  "localhost:3000/search?q=contextcap&page=2",
  "Uncaught TypeError: Cannot read properties of undefined (reading 'items')",
  "    at SearchResults (SearchResults.tsx:42:18)",
  "    at useSearch (useSearch.ts:17:9)",
  "検索結果  2 ページ目",
  "前へ    次へ",
];

const bar = (w: number, o = 0.16, h = 10) => (
  <div style={{ width: w, height: h, borderRadius: h / 2, background: `rgba(255,255,255,${o})` }} />
);

export const FakeScreen: React.FC<{ kind: ScreenKind; width: number; height: number; seed?: number }> = ({
  kind,
  width,
  height,
  seed = 0,
}) => {
  const r = (n: number) => ((Math.sin((seed + 1) * 9301 + n * 49297) + 1) / 2);
  return (
    <div
      style={{
        width,
        height,
        borderRadius: 14,
        overflow: "hidden",
        background: "#0F1012",
        border: `1px solid ${C.line}`,
        display: "flex",
        flexDirection: "column",
      }}
    >
      <div style={{ height: 34, display: "flex", alignItems: "center", gap: 8, padding: "0 14px", borderBottom: `1px solid ${C.line}` }}>
        {[0, 1, 2].map((i) => (
          <div key={i} style={{ width: 9, height: 9, borderRadius: 5, background: "rgba(255,255,255,0.12)" }} />
        ))}
        <div style={{ marginLeft: 12 }}>{bar(width * 0.38, 0.1, 12)}</div>
      </div>
      <div style={{ flex: 1, padding: 22, display: "flex", flexDirection: "column", gap: 12 }}>
        {kind === "error" && (
          <div style={{ fontFamily: FONT.mono, fontSize: Math.round(width / 46), lineHeight: 1.6 }}>
            {ERROR_SCREEN_LINES.map((l, i) => (
              <div key={i} style={{ color: i === 1 ? "#D9A3A3" : i < 4 ? C.dim : C.faint, whiteSpace: "nowrap" }}>
                {l}
              </div>
            ))}
          </div>
        )}
        {kind === "code" &&
          Array.from({ length: 11 }, (_, i) => (
            <div key={i} style={{ display: "flex", gap: 10, paddingLeft: (i % 4) * 22 }}>
              {bar(width * (0.12 + 0.2 * r(i)), 0.2)}
              {bar(width * (0.08 + 0.25 * r(i + 20)), i % 3 === 0 ? 0.28 : 0.12)}
            </div>
          ))}
        {kind === "chat" &&
          Array.from({ length: 5 }, (_, i) => (
            <div key={i} style={{ display: "flex", justifyContent: i % 2 ? "flex-end" : "flex-start" }}>
              <div style={{ padding: 12, borderRadius: 12, background: i % 2 ? "rgba(143,163,184,0.16)" : "rgba(255,255,255,0.06)", display: "flex", flexDirection: "column", gap: 7 }}>
                {bar(width * (0.18 + 0.22 * r(i)), 0.22, 8)}
                {bar(width * (0.1 + 0.15 * r(i + 9)), 0.14, 8)}
              </div>
            </div>
          ))}
        {kind === "doc" && (
          <>
            {bar(width * 0.5, 0.3, 18)}
            {Array.from({ length: 9 }, (_, i) => bar(width * (0.55 + 0.3 * r(i)), 0.12, 9))}
          </>
        )}
      </div>
    </div>
  );
};
