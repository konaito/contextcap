// AGP 9.0 以降は Kotlin サポートが AGP に内蔵されているため、
// org.jetbrains.kotlin.android プラグインは宣言しない（宣言するとビルドが失敗する）。
// https://developer.android.com/build/releases/agp-9-0-0-release-notes
//
// Compose Compiler は別プラグイン。AGP が内蔵する KGP と同じバージョンを指定する
// （バージョンがずれると Compose のコンパイルが落ちる）。
plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
