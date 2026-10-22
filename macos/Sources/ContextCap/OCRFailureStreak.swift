import Foundation

/// 撮影直後の OCR が続けて失敗した回数を数え、Vision が壊れた状態に入ったかを判定する。
///
/// 実測（2026-10-09・10・21・22）では、スリープ復帰の直後から全件が
/// `CRImageReaderError 1` で失敗し続け、**プロセスを再起動するまで戻らなかった**
/// （10-22 は 08:30〜16:04 に 4,983 件、10-21 は 12:50〜23:02 に 4,711 件）。
/// 失敗した画像を後から別プロセスで読ませると通るので、画像ではなくプロセス内の状態の問題。
/// 復帰のたびに起きるわけではない。壊れる仕組みそのものは確かめていない。
///
/// 純粋なロジックだけ置く（`scripts/run-tests.sh` から直接コンパイルして確かめる）。
struct OCRFailureStreak {
    let threshold: Int
    private(set) var count = 0

    init(threshold: Int) {
        self.threshold = threshold
    }

    /// 結果を 1 件入れる。続けての失敗がしきい値に**ちょうど達した 1 回だけ** true を返す。
    /// 成功が 1 件でも入れば数え直す
    mutating func record(failed: Bool) -> Bool {
        guard failed else {
            count = 0
            return false
        }
        count += 1
        return count == threshold
    }
}

/// 自動の再起動を繰り返さないための判定。前回から `minInterval` 秒経っていなければ見送る
enum OCRRelaunchPolicy {
    static func shouldRelaunch(now: Date, last: Date?, minInterval: TimeInterval) -> Bool {
        guard let last else { return true }
        return now.timeIntervalSince(last) >= minInterval
    }
}
