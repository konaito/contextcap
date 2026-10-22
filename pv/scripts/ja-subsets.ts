// 映像に出てくる日本語の文字が入っている Noto Sans JP の断片（[0]〜[119]）を調べ、
// src/film/jaSubsets.ts に書き出す。断片を全部読むと 1 ウェイト 120 個のフォントを取りに行くため。
// セリフを変えたら流し直す:  bun scripts/ja-subsets.ts
// （src 以下の .ts/.tsx の非 ASCII 文字をすべて拾うので、コメントの文字の分だけ多めに入る）
import { readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { getInfo } from "@remotion/google-fonts/NotoSansJP";

const root = new URL("../src/", import.meta.url).pathname;
const files: string[] = [];
const walk = (dir: string) => {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p);
    else if (/\.(ts|tsx)$/.test(name) && !name.startsWith("jaSubsets")) files.push(p);
  }
};
walk(root);
const text = files.map((f) => readFileSync(f, "utf8")).join("");
// 全角の記号も含めて拾う。行頭の装飾（⏺ など）も Noto Sans JP に無ければ下で止まる
const chars = [...new Set([...text].filter((c) => c.codePointAt(0)! > 0x7f))];

const ranges = getInfo().unicodeRanges as Record<string, string>;
const parsed = Object.entries(ranges).map(([subset, spec]) => ({
  subset,
  spans: spec.split(",").map((part) => {
    const [a, b] = part.trim().replace(/^U\+/i, "").split("-");
    return [parseInt(a, 16), parseInt(b ?? a, 16)] as const;
  }),
}));

const needed = new Set<string>();
const missing: string[] = [];
for (const c of chars) {
  const cp = c.codePointAt(0)!;
  const hit = parsed.find((p) => p.spans.some(([lo, hi]) => cp >= lo && cp <= hi));
  if (hit) needed.add(hit.subset);
  else missing.push(c);
}
if (missing.length > 0) {
  console.error(`Noto Sans JP のどの断片にも無い文字: ${missing.join(" ")}（画面に出すなら別の書体が要る）`);
}
const sorted = [...needed].sort((a, b) => parseInt(a.slice(1)) - parseInt(b.slice(1)));
const out = `// scripts/ja-subsets.ts が生成する。手で書き換えない\nexport const JA_SUBSETS = ${JSON.stringify(sorted)} as const;\n`;
writeFileSync(join(root, "film", "jaSubsets.ts"), out);
console.log(`${chars.length} 文字 → ${sorted.length} 断片 → src/film/jaSubsets.ts`);
