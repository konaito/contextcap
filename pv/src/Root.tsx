import React from "react";
import { Composition } from "remotion";
import { Film } from "./film/Film";
import { FPS as FILM_FPS, TOTAL as FILM_TOTAL } from "./film/timeline";
import { Promo } from "./Promo";
import { FPS, HEIGHT, TOTAL_FRAMES, WIDTH } from "./timeline";

export const RemotionRoot: React.FC = () => (
  <>
    {/* 2 本目。参照映像の分析と哲学の掘り下げをもとに作り直した版 */}
    <Composition id="ContextCapFilm" component={Film} durationInFrames={FILM_TOTAL} fps={FILM_FPS} width={WIDTH} height={HEIGHT} />
    {/* 1 本目（文字だけの版）。比較用に残す */}
    <Composition id="ContextCapPromo" component={Promo} durationInFrames={TOTAL_FRAMES} fps={FPS} width={WIDTH} height={HEIGHT} />
  </>
);
