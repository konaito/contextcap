import AppKit
import Foundation

/// **キー入力を日付ごとの JSONL に残す。macOS 版のみ。**
///
/// あとで解析するとき、画面の OCR だけでは「何を打ったか」「どのショートカットを
/// 使ったか」が復元しにくい。IME の変換前ローマ字や、⌘系ショートカットは画面に
/// 文字として出ないことが多いためこれを補う。撮影と同じく **ローカル保存のみ**で、
/// 送信機構は持たない。
///
/// 記録するのは押下（keyDown）だけ。離す（keyUp）は取らない。1 行の形:
///
///     {"app":"com.google.Chrome","key":"c","mods":["cmd"],"t":"162303_273"}
///
/// - `t`   … 撮影ファイルと同じ `HHmmss_SSS`（`CaptureFile.timeStem`）
/// - `key` … 修飾を無視した基底キー（`charactersIgnoringModifiers`）か、特殊キーの名前
/// - `mods`… 押されていた修飾キー。無ければ省く。ショートカットの識別に使う
/// - `app` … 前面アプリの bundle id。⌘W の意味はアプリで変わるので必ず添える
///
/// 保持は **無期限**。`Retention` は `.jpg` しか消さないので、この JSONL は触られない
/// （`gaps.jsonl` / `blackouts.jsonl` と同じ扱い）。テキストなので容量は画像より桁違いに小さい。
///
/// ## 権限
/// グローバルなキー監視には **アクセシビリティ（Accessibility）権限**が要る。
/// 無いと `NSEvent.addGlobalMonitorForEvents` はイベントを 1 件も配信しない（黙って空振り）。
/// `AXIsProcessTrusted()` で確認し、無ければプロンプトを出して 10 秒ごとに再試行する。
///
/// ## パスワードは基本的に入らない
/// パスワード欄などセキュア入力中は OS が secure event input を有効にし、
/// グローバルモニタにはそのキーが届かない。とはいえ通常テキストは全部残るので、
/// 保存先（`~/ContextCap`）は他人に渡さない前提で扱うこと。
@MainActor
final class KeyLog {
    static let fileName = "keys.jsonl"

    private let root: URL
    private var monitor: Any?
    private var retryTimer: Timer?

    private let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = CaptureFile.dayFormat
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    init(root: URL) {
        self.root = root
    }

    /// 監視状態。権限があり監視が張れていれば true
    var isActive: Bool { monitor != nil }

    /// 権限があれば監視を始める。無ければプロンプトを出し、付与されるまで再試行する。
    func start() {
        if isTrusted(prompt: false) {
            installMonitor()
        } else {
            _ = isTrusted(prompt: true)
            scheduleRetry()
        }
    }

    func stop() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
        retryTimer?.invalidate()
        retryTimer = nil
    }

    // MARK: - 権限

    private func isTrusted(prompt: Bool) -> Bool {
        let key = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        return AXIsProcessTrustedWithOptions([key: prompt] as CFDictionary)
    }

    private func scheduleRetry() {
        guard retryTimer == nil else { return }
        retryTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.monitor == nil else { return }
                guard self.isTrusted(prompt: false) else { return }
                self.retryTimer?.invalidate()
                self.retryTimer = nil
                self.installMonitor()
            }
        }
    }

    private func installMonitor() {
        guard monitor == nil else { return }
        // グローバルモニタは自アプリ以外のキーだけ届く。メニューバー常駐で
        // キーウィンドウを持たないため、実質すべての入力を拾える。
        monitor = NSEvent.addGlobalMonitorForEvents(matching: [.keyDown]) { [weak self] event in
            MainActor.assumeIsolated { self?.record(event) }
        }
    }

    // MARK: - 記録

    private func record(_ event: NSEvent, at date: Date = Date()) {
        let day = dayFormatter.string(from: date)
        let dayDir = root.appendingPathComponent(day)
        try? FileManager.default.createDirectory(at: dayDir, withIntermediateDirectories: true)

        var object: [String: Any] = [
            "t": CaptureFile.timeStem(date),
            "key": keyName(for: event),
        ]
        let mods = modifierNames(event.modifierFlags)
        if !mods.isEmpty { object["mods"] = mods }
        if let app = NSWorkspace.shared.frontmostApplication?.bundleIdentifier {
            object["app"] = app
        }

        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let json = String(data: data, encoding: .utf8) else { return }
        JSONLWriter.append(json + "\n", to: dayDir.appendingPathComponent(Self.fileName))
    }

    /// 押されていた修飾キー。CapsLock は雑音なので除く。順序は cmd→opt→ctrl→shift→fn で固定。
    private func modifierNames(_ flags: NSEvent.ModifierFlags) -> [String] {
        var names: [String] = []
        if flags.contains(.command) { names.append("cmd") }
        if flags.contains(.option) { names.append("opt") }
        if flags.contains(.control) { names.append("ctrl") }
        if flags.contains(.shift) { names.append("shift") }
        if flags.contains(.function) { names.append("fn") }
        return names
    }

    /// 特殊キーは名前で、印字キーは修飾を無視した基底文字で表す。
    private func keyName(for event: NSEvent) -> String {
        if let name = Self.specialKeys[event.keyCode] { return name }
        if let chars = event.charactersIgnoringModifiers, !chars.isEmpty,
           chars.unicodeScalars.allSatisfy({ !$0.properties.isDefaultIgnorableCodePoint && $0.value >= 0x20 }) {
            return chars
        }
        return "key\(event.keyCode)"
    }

    /// 印字されない特殊キーの keyCode → 名前。charactersIgnoringModifiers では
    /// 制御文字や空になって判別できないものだけを載せる。
    private static let specialKeys: [UInt16: String] = [
        36: "return", 48: "tab", 49: "space", 51: "delete", 53: "escape",
        76: "enter", 117: "forwarddelete",
        123: "left", 124: "right", 125: "down", 126: "up",
        115: "home", 119: "end", 116: "pageup", 121: "pagedown",
        122: "f1", 120: "f2", 99: "f3", 118: "f4", 96: "f5", 97: "f6",
        98: "f7", 100: "f8", 101: "f9", 109: "f10", 103: "f11", 111: "f12",
    ]
}
