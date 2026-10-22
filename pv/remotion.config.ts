import { Config } from "@remotion/cli/config";

Config.setVideoImageFormat("jpeg");
Config.setOverwriteOutput(true);

// 同梱の Headless Shell は展開が途中で止まる環境があるため、
// 手元の Google Chrome があればそれを使う。
const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
if (process.env.REMOTION_BROWSER_EXECUTABLE) {
  Config.setBrowserExecutable(process.env.REMOTION_BROWSER_EXECUTABLE);
} else if (process.platform === "darwin") {
  Config.setBrowserExecutable(chrome);
}
