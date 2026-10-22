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
        check(!s.record(failed: true) && !s.record(failed: true), "しきい値を超えて続いても 2 回目は知らせない")
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

        if failures > 0 {
            print("\(failures) 件失敗")
            exit(1)
        }
        print("全部通った")
    }
}
