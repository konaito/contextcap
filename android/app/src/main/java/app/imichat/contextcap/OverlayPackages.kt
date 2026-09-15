package app.imichat.contextcap

/// 「前面アプリ」として記録してはいけないパッケージの判定。
///
/// **なぜ要るか（2026-08-20 に実データで発覚）。**
/// `TYPE_WINDOW_STATE_CHANGED` は通知シェード・ステータスバー・音量バー・ロック画面が
/// 開いた瞬間にも飛んでくる。ところが**閉じた時に元アプリのイベントは飛ばない**
/// （元アプリのウィンドウは変わっていないため）。そのまま記録すると
/// `com.android.systemui` に張り付いたまま二度と戻らない。
///
/// 実機 7 日分（25,400 フレーム）で測ったところ、**37%（9,483 枚）が systemui に誤帰属**
/// していた。中身を OCR で読むと実際は YouTube や X の画面。区間数 871・合計 64 時間、
/// 最長の 1 区間は 348 分。キーボード（IME）も同じ理由で 781 枚が誤って前面扱いになっていた。
///
/// 対処は「上書きしない」だけでよい。オーバーレイが出ている間も、
/// ユーザーが見ているアプリは直前のままだから。
///
/// **ランチャーは除外しない。** ホーム画面は実際に前面にあり、そこに滞在した事実は残したい。
object OverlayPackages {

    /// 通知シェード・ステータスバー・音量・ロック画面がここから飛ぶ
    const val SYSTEM_UI = "com.android.systemui"

    /// 機種に依らず固定で除外するもの。`pixeldisplayservice` は Pixel の画面制御で、
    /// 実データで 82 枚（うち 75 枚が真っ黒）を前面アプリとして記録していた
    private val FIXED = setOf(SYSTEM_UI, "com.android.pixeldisplayservice")

    /// `imePackages` は実行時に `InputMethodManager` から取った有効な IME のパッケージ集合。
    /// 取得に失敗したら空集合を渡す（その場合も systemui の分だけは必ず効く）。
    /// IME を決め打ちのリストにしないのは、機種と設定で変わるため。
    fun isOverlay(packageName: String, imePackages: Set<String>): Boolean =
        packageName in FIXED || packageName in imePackages
}
