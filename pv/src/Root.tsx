import React from "react";
import { Composition } from "remotion";
import { Film } from "./film/Film";
import { FPS, TOTAL } from "./film/timeline";

export const RemotionRoot: React.FC = () => (
  <Composition id="ContextCapFilm" component={Film} durationInFrames={TOTAL} fps={FPS} width={1920} height={1080} />
);
