// OCRFailureStreak / OCRRelaunchPolicy の判定を確かめる。
// macOS 側にテストターゲットが無いので、対象ソースを直接コンパイルして叩く。
import Foundation

@main
struct OCRStreakTest {
    static var failures = 0

    static func check(_ ok: Bool, _ name: String) {
        print(ok ? "ok   \(name)" : "FAIL \(name)")
        if !ok { failures += 1 }
    }

    static func main() {
        var s = OCRFailureStreak(threshold: 3)
        check(!s.record(failed: true) && !s.record(failed: true), "しきい値未満では知らせない")
        check(s.record(failed: true), "3 回続けて失敗したら知らせる")
        check(!s.record(failed: true) && !s.record(failed: true), "しきい値を超えてすぐには知らせない")
        check(s.record(failed: true), "失敗が続けば、しきい値の倍数ごとにまた知らせる（#18）")
        // 1 時間の待ちが明けるまで（5 秒に 1 枚で 720 枚）失敗が続いても、知らせは来続ける
        var long = OCRFailureStreak(threshold: 10)
        let alerts = (0..<720).filter { _ in long.record(failed: true) }.count
        check(alerts == 72, "720 回続けて失敗したら 72 回知らせる")
        check(!s.record(failed: false) && s.count == 0, "成功が入れば数え直す")
        _ = s.record(failed: true); _ = s.record(failed: true)
        _ = s.record(failed: false)
        check(!s.record(failed: true) && s.count == 1, "途中に成功があれば続けて数えない")

        let now = Date()
        check(OCRRelaunchPolicy.shouldRelaunch(now: now, last: nil, minInterval: 3600), "初回は再起動する")
        check(!OCRRelaunchPolicy.shouldRelaunch(now: now, last: now.addingTimeInterval(-60), minInterval: 3600),
              "1 時間以内なら見送る")
        check(OCRRelaunchPolicy.shouldRelaunch(now: now, last: now.addingTimeInterval(-3601), minInterval: 3600),
              "1 時間を過ぎたら再起動する")

        // 2026-10-23 の流れ（#18）: 23:54 に再起動し、18 分後にまた壊れて 5 秒おきに失敗し続ける
        let restarted = Date(timeIntervalSince1970: 0)
        var streak = OCRFailureStreak(threshold: 10)
        var relaunchedAt: Date?
        for i in 0..<2000 {
            let at = restarted.addingTimeInterval(18 * 60 + Double(i) * 5)
            if streak.record(failed: true),
               OCRRelaunchPolicy.shouldRelaunch(now: at, last: restarted, minInterval: 3600) {
                relaunchedAt = at
                break
            }
        }
        let waited = relaunchedAt.map { $0.timeIntervalSince(restarted) } ?? .infinity
        check(waited >= 3600 && waited < 3600 + 50, "1 時間の待ちが明けてから 50 秒以内に再起動する")

        if failures > 0 {
            print("\(failures) 件失敗")
            exit(1)
        }
        print("全部通った")
    }
}
