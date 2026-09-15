import AppKit
import Foundation

/// **撮影が途切れた理由**を日付ごとの JSONL に残す。macOS 版のみ。
///
/// `blackouts.jsonl` は「届いたフレームが黒だった区間」で、これは別物。
/// macOS でスクショが無い時間には少なくとも 4 つの原因があり、画像を見ても区別できない:
///
///   1. 本体スリープ  — タイマーごと止まる。`willSleepNotification` でしか捉えられない
///   2. ディスプレイ消灯 — 本体は起きている
///   3. 画面ロック    — ScreenCaptureKit が失敗する。「起きているが離席」の signal
///   4. アプリ停止    — 起動・終了
///
/// これが無いと、解析側は「13:20-17:07 に何も無い」から「PC の前にいなかった」を
/// 推測するしかない。実際にはスリープだったのか離席だったのか判定できない。
///
/// `blackouts.jsonl` と同じく変化点方式で、状態が変わった瞬間だけ 1 行書く。
/// 日付をまたいで異常状態が続いていたら、翌日のファイルの先頭にも同じ状態を書き直す
/// （日ごとのファイルだけ見れば区間が復元できるようにするため）。
///
/// **Android には対応物が無い。**`{"t":...,"state":...}` の形だけ `blackouts.jsonl` と
/// 揃えてあるが、状態名は macOS 固有。Android 側の契約には手を入れていない。
@MainActor
final class GapLog {
    static let fileName = "gaps.jsonl"

    enum State: String {
        case appStart = "app_start"
        case appStop = "app_stop"
        case sleep
        case wake
        case screensOff = "screens_off"
        case screensOn = "screens_on"
        case locked
        case unlocked
        case captureFailed = "capture_failed"
        case captureOk = "capture_ok"

        /// 撮影が取れない状態か。日付をまたいだ時に書き直す対象
        var isGap: Bool {
            switch self {
            case .sleep, .screensOff, .locked, .captureFailed: return true
            case .appStart, .appStop, .wake, .screensOn, .unlocked, .captureOk: return false
            }
        }

        /// この状態が解消される相手
        var resolvedBy: State? {
            switch self {
            case .sleep: return .wake
            case .screensOff: return .screensOn
            case .locked: return .unlocked
            case .captureFailed: return .captureOk
            default: return nil
            }
        }
    }

    private let root: URL
    private var lastDay: String?
    /// 継続中の異常状態。日付をまたぐ時に書き直す
    private var active: Set<State> = []

    private let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = CaptureFile.dayFormat
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    init(root: URL) {
        self.root = root
    }

    // MARK: - 記録

    /// 状態変化を 1 行書く。同じ状態の繰り返しは書かない。
    func record(_ state: State, reason: String? = nil, at date: Date = Date()) {
        // 継続中でない状態の解消は書かない（起動直後に unlocked を書かない）
        if let target = State.allGapStates.first(where: { $0.resolvedBy == state }) {
            guard active.contains(target) else { return }
            active.remove(target)
        } else if state.isGap {
            guard !active.contains(state) else { return }
            active.insert(state)
        }

        let day = dayFormatter.string(from: date)
        let dayDir = root.appendingPathComponent(day)
        try? FileManager.default.createDirectory(at: dayDir, withIntermediateDirectories: true)

        // 日付が変わったら、継続中の異常状態をその日の先頭に書き直す
        if let previous = lastDay, previous != day {
            for carried in active where carried != state {
                write(carried, reason: "carried over", at: date, dayDir: dayDir)
            }
        }
        lastDay = day

        write(state, reason: reason, at: date, dayDir: dayDir)
    }

    private func write(_ state: State, reason: String?, at date: Date, dayDir: URL) {
        var object: [String: Any] = [
            "t": CaptureFile.timeStem(date),
            "state": state.rawValue,
        ]
        // reason は OS 由来の自由文字列。引用符や改行が混ざるので手組みしない
        if let reason, !reason.isEmpty { object["reason"] = reason }
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let json = String(data: data, encoding: .utf8) else { return }
        JSONLWriter.append(json + "\n", to: dayDir.appendingPathComponent(Self.fileName))
    }

    // MARK: - OS 通知の購読

    /// スリープ・消灯・ロックを購読する。**タイマーは寝ている間止まるので、
    /// この通知が唯一の手がかりになる。**
    func startObserving() {
        let workspace = NSWorkspace.shared.notificationCenter
        let pairs: [(Notification.Name, State)] = [
            (NSWorkspace.willSleepNotification, .sleep),
            (NSWorkspace.didWakeNotification, .wake),
            (NSWorkspace.screensDidSleepNotification, .screensOff),
            (NSWorkspace.screensDidWakeNotification, .screensOn),
        ]
        for (name, state) in pairs {
            workspace.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.record(state) }
            }
        }

        // ロックは NSWorkspace ではなく distributed notification でしか来ない
        let distributed = DistributedNotificationCenter.default()
        let lockPairs: [(String, State)] = [
            ("com.apple.screenIsLocked", .locked),
            ("com.apple.screenIsUnlocked", .unlocked),
        ]
        for (name, state) in lockPairs {
            distributed.addObserver(
                forName: Notification.Name(name), object: nil, queue: .main
            ) { [weak self] _ in
                MainActor.assumeIsolated { self?.record(state) }
            }
        }
    }
}

private extension GapLog.State {
    static var allGapStates: [GapLog.State] {
        [.sleep, .screensOff, .locked, .captureFailed]
    }
}
