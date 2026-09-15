import AppKit
import CoreGraphics
import Foundation
import ScreenCaptureKit

/// 5 秒間隔でプライマリディスプレイ全体を撮影して JPEG 保存する。
/// 前の撮影が終わっていない tick はスキップする（in-flight guard）。
///
/// **常に等倍で撮る。** 以前は Compactor が使った圧縮段を撮影プロファイルとして
/// 採用し、以降その解像度で撮っていた（適応式）。2026-08-18 に廃止した。
/// 容量超過で一度 g1 が採用されると新規撮影まで 1920/q60 に落ち、同一画像の統制実験で
/// 行近似一致が 75.1% まで下がることが分かったため。実際 `CaptureGeneration = 1` が
/// 永続化されていて、gen0 が 1 枚も撮られていない状態が続いていた。
///
/// 保存後は OCRIndexer に積むだけで、OCR の完了は待たない。撮影 tick を遅らせないため。
@MainActor
final class CaptureManager {
    enum State: Equatable {
        case running
        case paused
        case noPermission
    }

    static let interval: TimeInterval = 5.0
    static let fullResQuality: Double = 0.75

    private(set) var state: State = .paused
    private(set) var lastError: String?

    private let stats: StatsStore
    private let indexer: OCRIndexer
    private var timer: Timer?
    private var inFlight = false
    private let blackoutLog = BlackoutLog()
    private let gapLog: GapLog
    var onCapture: (() -> Void)?

    /// 直近の撮影が情報のない画面だったか（メニュー表示用）
    private(set) var lastUninformative = false

    init(stats: StatsStore, indexer: OCRIndexer) {
        self.stats = stats
        self.indexer = indexer
        self.gapLog = GapLog(root: stats.rootDirectory)
        gapLog.startObserving()
        gapLog.record(.appStart)
        // 適応撮影プロファイルは廃止した。残っていると次回起動で誤読するので消す
        UserDefaults.standard.removeObject(forKey: "CaptureGeneration")
    }

    /// 終了を残す。これが無いと、落ちた時と終了した時が区別できない
    func recordStop() {
        gapLog.record(.appStop)
    }

    // MARK: - 制御

    func start() {
        guard hasPermission() else {
            state = .noPermission
            CGRequestScreenCaptureAccess()
            return
        }
        guard state != .running else { return }
        state = .running
        timer = Timer.scheduledTimer(withTimeInterval: Self.interval, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                await self?.tick()
            }
        }
        // 起動直後にも 1 枚撮る
        Task { await tick() }
    }

    func pause() {
        timer?.invalidate()
        timer = nil
        state = .paused
    }

    func hasPermission() -> Bool {
        CGPreflightScreenCaptureAccess()
    }

    var profileText: String {
        "フル解像度 q\(Self.fullResQuality)"
    }

    // MARK: - 撮影

    private func tick() async {
        guard state == .running, !inFlight else { return }
        inFlight = true
        defer { inFlight = false }

        do {
            // ディスプレイ構成変更に追従するため毎回取り直す
            let content = try await SCShareableContent.excludingDesktopWindows(
                false, onScreenWindowsOnly: false
            )
            let mainID = CGMainDisplayID()
            guard let display = content.displays.first(where: { $0.displayID == mainID })
                ?? content.displays.first else {
                lastError = "ディスプレイが見つからない"
                return
            }

            let filter = SCContentFilter(display: display, excludingWindows: [])
            let config = SCStreamConfiguration()
            let scale = CGFloat(filter.pointPixelScale)
            // 常に等倍。撮影段階で縮めると OCR が読めなくなるだけで、速くもならない
            config.width = Int(CGFloat(display.width) * scale)
            config.height = Int(CGFloat(display.height) * scale)
            config.showsCursor = true
            config.captureResolution = .best

            let image = try await SCScreenshotManager.captureImage(
                contentFilter: filter, configuration: config
            )

            let now = Date()
            // 情報のない画面はファイルを作らずに捨てるので nil が返る
            let saved = try save(image: image, at: now)
            if let saved {
                stats.recordCapture(bytes: saved.bytes, date: now)
                // 撮った瞬間に OCR へ。完了は待たない（待つと tick が詰まる）
                indexer.enqueue(saved.url)
            }
            lastError = nil
            gapLog.record(.captureOk, at: now)
            onCapture?()
        } catch {
            // スリープ・画面ロック中などは失敗する。無視して次の tick へ。
            // ただし「なぜ撮れなかったか」は残す（残さないと解析側で離席と区別できない）
            lastError = error.localizedDescription
            gapLog.record(.captureFailed, reason: error.localizedDescription)
        }
    }

    /// 情報のない画面（DRM 保護・消灯直前）は**ファイルを作らずに捨てる**。
    /// 捨てた場合は nil を返す。
    /// 判定は必ず書き込みの前に置く（後段で落とすと MiaChat #1523 と同じ構図になる）。
    private func save(image: CGImage, at date: Date) throws -> (url: URL, bytes: Int64)? {
        let rep = NSBitmapImageRep(cgImage: image)
        guard let data = rep.representation(
            using: .jpeg,
            properties: [.compressionFactor: Self.fullResQuality]
        ) else {
            throw NSError(
                domain: "ContextCap", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "JPEG エンコード失敗"]
            )
        }

        let dayDir = stats.rootDirectory.appendingPathComponent(CaptureFile.dayDirName(date))
        try FileManager.default.createDirectory(at: dayDir, withIntermediateDirectories: true)
        let timeStem = CaptureFile.timeStem(date)

        let uninformative = Self.isUninformative(jpeg: data)
        blackoutLog.record(dayDir: dayDir, date: date, stem: timeStem, uninformative: uninformative)
        lastUninformative = uninformative
        if uninformative { return nil }

        // 等倍でしか撮らなくなったので gen 接尾辞は付けない。
        // 既存アーカイブの .g1 を読む側は CaptureFile.legacyGenerationSuffixes が面倒を見る
        let fileURL = dayDir.appendingPathComponent("\(timeStem).jpg")
        try data.write(to: fileURL, options: .atomic)
        return (fileURL, Int64(data.count))
    }

    /// 保存する価値のない画面かどうか。**エンコード済みの JPEG から読み直して**判定する。
    /// Android 版と同じ経路にすることで、JPEG のブロックノイズの乗り方まで揃う
    /// （原寸 CGImage を直接見ると完全な 0 が残り、判定が Android より厳しくなる）。
    static func isUninformative(jpeg: Data) -> Bool {
        guard let pixels = thumbnailARGB(jpeg: jpeg, maxPixel: blacknessMaxPixel) else {
            return false
        }
        return FrameBlackness.isUninformative(
            pixels: pixels.values, width: pixels.width, height: pixels.height
        )
    }

    /// Android 側 `CaptureService.BLACKNESS_MAX_PIXEL` と同値
    private static let blacknessMaxPixel = 160

    private static func thumbnailARGB(
        jpeg: Data, maxPixel: Int
    ) -> (values: [UInt32], width: Int, height: Int)? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil),
              let thumb = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
        else { return nil }

        let width = thumb.width
        let height = thumb.height
        guard width > 0, height > 0 else { return nil }

        var buffer = [UInt32](repeating: 0, count: width * height)
        let bitmapInfo = CGImageAlphaInfo.premultipliedFirst.rawValue
            | CGBitmapInfo.byteOrder32Little.rawValue
        guard let ctx = buffer.withUnsafeMutableBytes({ raw in
            CGContext(
                data: raw.baseAddress, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: bitmapInfo
            )
        }) else { return nil }
        ctx.draw(thumb, in: CGRect(x: 0, y: 0, width: width, height: height))
        return (buffer, width, height)
    }
}
