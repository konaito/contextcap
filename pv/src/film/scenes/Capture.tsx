import React from "react";
import { AbsoluteFill, Easing, interpolate, useCurrentFrame, useVideoConfig } from "remotion";
import { C, FONT } from "../look";
import { FakeScreen, kindOf } from "../parts/FakeScreen";
import { clamp, enter, focusIn, ramp } from "../parts/motion";
import { SNAP } from "../look";
import { BEAT, FACTS } from "../timeline";

/// 1 拍ごとに 1 枚撮れる。拍に合わせて積み、間は止める
const CAPTURE_EVERY = BEAT;
const CAPTURE_START = 2;
const CAPTURE_CARDS = 18;

/// 撮る。画面のカードが一定の拍で前に増え、古いものが奥へ送られていく（Recall の積層）。
/// 奥へ行くほど時刻のラベルが飛び、最後は記録の始まりの日付になる
const AGO = ["今", "5秒前", "10秒前", "1分前", "10分前", "1時間前", "3時間前", "昨日", "2日前", "先週", "先月", "8月3日"];

const CARD_W = 760;
const CARD_H = 476;

export const Capture: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();
  const k = (frame - CAPTURE_START) / CAPTURE_EVERY;
  // 1 拍ごとに送って、拍の間は止める（撮影の拍）
  const step = (a: number) => Math.floor(a) + Easing.out(Easing.cubic)(Math.min(1, (a % 1) / 0.35));

  const dolly = interpolate(frame, [0, durationInFrames], [0, 1], { ...clamp, easing: Easing.inOut(Easing.sin) });
  const inFade = ramp(frame, 0, 4);
  const outFade = 1 - ramp(frame, durationInFrames - 6, durationInFrames);

  const headline = enter(frame, 4, fps, SNAP);
  const counterT = interpolate(frame, [BEAT, durationInFrames - BEAT * 2], [0, 1], {
    ...clamp,
    easing: Easing.inOut(Easing.cubic),
  });
  const counter = Math.round(FACTS.shots * counterT);
  const counterIn = enter(frame, BEAT, fps, SNAP);

  return (
    <AbsoluteFill style={{ opacity: inFade * outFade }}>
      <AbsoluteFill style={{ perspective: 2200, perspectiveOrigin: "60% 45%" }}>
        <div
          style={{
            position: "absolute",
            left: 1180 - dolly * 120,
            top: 300,
            transformStyle: "preserve-3d",
            transform: `rotateX(10deg) rotateY(${-34 + dolly * 6}deg)`,
          }}
        >
          {Array.from({ length: CAPTURE_CARDS }, (_, j) => {
            const age = k - j;
            if (age < 0) return null;
            const a = step(age);
            const p = Math.min(1, age / 0.5);
            const far = 1 - interpolate(a, [9, 14], [0, 1], clamp);
            const slot = Math.min(AGO.length - 1, Math.floor(a + 0.25));
            return (
              <div
                key={j}
                style={{
                  position: "absolute",
                  transformStyle: "preserve-3d",
                  transform: `translate3d(${-a * 170}px, ${-a * 6}px, ${-a * 150 + (1 - p) * 220}px)`,
                  opacity: p * far,
                  filter: `blur(${Math.max(0, a - 2.5) * 0.9 + (1 - p) * 8}px)`,
                }}
              >
                <div style={{ position: "relative" }}>
                  <FakeScreen kind={kindOf(j)} width={CARD_W} height={CARD_H} seed={j} />
                  {/* 撮れた瞬間だけ縁が光る */}
                  <div
                    style={{
                      position: "absolute",
                      inset: 0,
                      borderRadius: 14,
                      border: `1px solid ${C.signal}`,
                      boxShadow: `0 0 40px ${C.signal}55`,
                      opacity: interpolate(age, [0, 0.15, 0.9], [0, 1, 0], clamp),
                    }}
                  />
                </div>
                <div
                  style={{
                    marginTop: 18,
                    fontFamily: FONT.mono,
                    fontSize: 22,
                    color: slot === 0 ? C.text : C.dim,
                    opacity: interpolate(a, [0, 0.5, 1.4, 1.9], [1, 1, 0.6, 0], clamp),
                  }}
                >
                  {AGO[slot]}
                </div>
              </div>
            );
          })}
        </div>
      </AbsoluteFill>

      <div style={{ position: "absolute", left: 120, top: 120, ...focusIn(headline, 12) }}>
        <div style={{ fontFamily: FONT.mono, fontSize: 20, letterSpacing: "0.18em", color: C.dim }}>
          EVERY 5 SECONDS
        </div>
        <div
          style={{
            marginTop: 20,
            fontFamily: FONT.ja,
            fontWeight: 400,
            fontSize: 64,
            letterSpacing: "0.04em",
            color: C.text,
          }}
        >
          5秒に1枚、撮り続ける。
        </div>
      </div>

      <div style={{ position: "absolute", left: 120, bottom: 110, ...focusIn(counterIn, 10) }}>
        <div
          style={{
            fontFamily: FONT.mono,
            fontWeight: 300,
            fontSize: 112,
            letterSpacing: "-0.02em",
            color: C.text,
            fontVariantNumeric: "tabular-nums",
          }}
        >
          {counter.toLocaleString("en-US")}
        </div>
        <div style={{ marginTop: 6, fontFamily: FONT.mono, fontSize: 22, letterSpacing: "0.12em", color: C.dim }}>
          SCREENS · SINCE {FACTS.since}
        </div>
      </div>
    </AbsoluteFill>
  );
};
