// 全撮影画像の知覚シグネチャを取り、隣接フレームの差の分布を出す。
// OCR（1 枚 ~19 秒）を全量に掛けると 26 時間かかるので、その前に重複を落とすための土台。
//
//   swiftc -O -o /tmp/frame-hash scripts/frame-hash.swift
//   /tmp/frame-hash --db ~/ContextCap-analysis/frames.sqlite
//   /tmp/frame-hash --db ~/ContextCap-analysis/frames.sqlite --report
//
// シグネチャは 32x32 のグレースケール（1024 バイト）をそのまま持つ。
// dHash 64bit のような粗い指紋にしない: 同じレイアウトで本文だけ違う画面
// （エディタ・チャット）を同一と誤判定して、中身ごと捨てるため。
// 距離は平均絶対差（0-255）。閾値は測ってから決める。

import CoreGraphics
import Foundation
import ImageIO
import SQLite3

let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
let sigN = 32

var rootPath = FileManager.default.homeDirectoryForCurrentUser
    .appendingPathComponent("ContextCap")
var dbPath = FileManager.default.homeDirectoryForCurrentUser
    .appendingPathComponent("ContextCap-analysis/frames.sqlite")
var reportOnly = false
var limit: Int?

var argIt = CommandLine.arguments.dropFirst().makeIterator()
while let a = argIt.next() {
    switch a {
    case "--root": if let v = argIt.next() { rootPath = URL(fileURLWithPath: (v as NSString).expandingTildeInPath) }
    case "--db": if let v = argIt.next() { dbPath = URL(fileURLWithPath: (v as NSString).expandingTildeInPath) }
    case "--report": reportOnly = true
    case "--limit": if let v = argIt.next() { limit = Int(v) }
    default: break
    }
}

// MARK: - 命名規約（CaptureFile.swift と同じ解釈）

let rungSuffixes = ["g1", "g2", "g3"]

func generation(of url: URL) -> Int {
    let stem = url.deletingPathExtension().lastPathComponent
    for (i, s) in rungSuffixes.enumerated() where stem.hasSuffix(".\(s)") { return i + 1 }
    return 0
}

func baseName(of url: URL) -> String {
    var stem = url.deletingPathExtension().lastPathComponent
    for s in rungSuffixes where stem.hasSuffix(".\(s)") { stem.removeLast(s.count + 1); break }
    return stem
}

let parser: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "yyyy-MM-dd HHmmss_SSS"
    f.locale = Locale(identifier: "en_US_POSIX")
    return f
}()

struct Shot { let url: URL; let day: String; let base: String; let gen: Int; let ts: Double }

func enumerateShots() -> [Shot] {
    let fm = FileManager.default
    guard let dirs = try? fm.contentsOfDirectory(at: rootPath,
                                                 includingPropertiesForKeys: [.isDirectoryKey],
                                                 options: [.skipsHiddenFiles]) else { return [] }
    let days = dirs
        .filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true }
        .map { $0.lastPathComponent }
        .filter { $0.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil }
        .sorted()
    var out: [Shot] = []
    for day in days {
        guard let files = try? fm.contentsOfDirectory(
            at: rootPath.appendingPathComponent(day), includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]) else { continue }
        var per: [Shot] = []
        for f in files where f.pathExtension.lowercased() == "jpg" {
            let b = baseName(of: f)
            guard let d = parser.date(from: "\(day) \(b)") else { continue }
            per.append(Shot(url: f, day: day, base: b, gen: generation(of: f),
                            ts: d.timeIntervalSince1970))
        }
        per.sort { $0.base < $1.base }
        out.append(contentsOf: per)
    }
    return out
}

// MARK: - シグネチャ

func signature(of url: URL) -> [UInt8]? {
    let thumbOpts: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceThumbnailMaxPixelSize: 256,
        kCGImageSourceCreateThumbnailWithTransform: true,
    ]
    guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
          let img = CGImageSourceCreateThumbnailAtIndex(src, 0, thumbOpts as CFDictionary)
    else { return nil }
    var buf = [UInt8](repeating: 0, count: sigN * sigN)
    guard let ctx = CGContext(data: &buf, width: sigN, height: sigN, bitsPerComponent: 8,
                              bytesPerRow: sigN, space: CGColorSpaceCreateDeviceGray(),
                              bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return nil }
    ctx.interpolationQuality = .medium
    ctx.draw(img, in: CGRect(x: 0, y: 0, width: sigN, height: sigN))
    return buf
}

func meanAbsDiff(_ a: [UInt8], _ b: [UInt8]) -> Double {
    guard a.count == b.count, !a.isEmpty else { return 255 }
    var s = 0
    for i in 0..<a.count { s += abs(Int(a[i]) - Int(b[i])) }
    return Double(s) / Double(a.count)
}

// MARK: - DB

var db: OpaquePointer?
try? FileManager.default.createDirectory(at: dbPath.deletingLastPathComponent(),
                                         withIntermediateDirectories: true)
guard sqlite3_open(dbPath.path, &db) == SQLITE_OK else {
    FileHandle.standardError.write("open failed\n".data(using: .utf8)!); exit(1)
}
func exec(_ s: String) {
    var e: UnsafeMutablePointer<CChar>?
    if sqlite3_exec(db, s, nil, nil, &e) != SQLITE_OK, let e {
        FileHandle.standardError.write("sqlite: \(String(cString: e))\n".data(using: .utf8)!)
        sqlite3_free(e)
    }
}
exec("PRAGMA journal_mode=WAL;")
exec("PRAGMA synchronous=NORMAL;")
exec("""
CREATE TABLE IF NOT EXISTS frames (
  day TEXT NOT NULL, base TEXT NOT NULL, gen INTEGER, ts REAL NOT NULL,
  path TEXT NOT NULL, sig BLOB NOT NULL, mean_lum REAL,
  PRIMARY KEY (day, base)
);
""")
exec("CREATE INDEX IF NOT EXISTS frames_ts ON frames(ts);")

func log(_ s: String) {
    let f = DateFormatter(); f.dateFormat = "HH:mm:ss"
    print("[\(f.string(from: Date()))] \(s)"); fflush(stdout)
}

// MARK: - レポート（隣接フレーム差の分布）

if reportOnly {
    var stmt: OpaquePointer?
    sqlite3_prepare_v2(db, "SELECT day, base, ts, sig FROM frames ORDER BY day, base;", -1, &stmt, nil)
    defer { sqlite3_finalize(stmt) }
    var prev: (day: String, sig: [UInt8])?
    var diffs: [Double] = []
    while sqlite3_step(stmt) == SQLITE_ROW {
        let day = String(cString: sqlite3_column_text(stmt, 0))
        let n = Int(sqlite3_column_bytes(stmt, 3))
        let p = sqlite3_column_blob(stmt, 3)!
        let sig = [UInt8](UnsafeBufferPointer(start: p.assumingMemoryBound(to: UInt8.self), count: n))
        if let pv = prev, pv.day == day { diffs.append(meanAbsDiff(pv.sig, sig)) }
        prev = (day, sig)
    }
    diffs.sort()
    guard !diffs.isEmpty else { log("データなし"); exit(0) }
    func pct(_ p: Double) -> Double { diffs[min(diffs.count - 1, Int(Double(diffs.count) * p))] }
    log("隣接フレーム差（平均絶対差 0-255）の分布  n=\(diffs.count)")
    for p in [0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95, 0.99] {
        print(String(format: "  p%.0f  %.3f", p * 100, pct(p)))
    }
    print("\n  閾値ごとの「残る枚数」（連続する同一画面を 1 枚に畳んだ場合）")
    for t in [0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 4.0] {
        let kept = diffs.filter { $0 > t }.count + 1
        print(String(format: "  thr %.2f → %6d 枚 (%.1f%%)  OCR 実測 0.5 枚/秒 なら %.1f 時間",
                     t, kept, 100.0 * Double(kept) / Double(diffs.count),
                     Double(kept) / 0.5 / 3600))
    }
    exit(0)
}

// MARK: - 収集

var doneKeys = Set<String>()
do {
    var stmt: OpaquePointer?
    sqlite3_prepare_v2(db, "SELECT day, base FROM frames;", -1, &stmt, nil)
    while sqlite3_step(stmt) == SQLITE_ROW {
        doneKeys.insert("\(String(cString: sqlite3_column_text(stmt, 0)))/\(String(cString: sqlite3_column_text(stmt, 1)))")
    }
    sqlite3_finalize(stmt)
}

var todo = enumerateShots().filter { !doneKeys.contains("\($0.day)/\($0.base)") }
if let n = limit { todo = Array(todo.prefix(n)) }
log("対象 \(todo.count) 枚 / 済 \(doneKeys.count) 枚")
if todo.isEmpty { log("処理対象なし"); exit(0) }

var ins: OpaquePointer?
sqlite3_prepare_v2(db, """
INSERT OR REPLACE INTO frames (day, base, gen, ts, path, sig, mean_lum) VALUES (?,?,?,?,?,?,?);
""", -1, &ins, nil)

let writeQ = DispatchQueue(label: "hash.write")
let start = Date()
var processed = 0
var failed = 0
exec("BEGIN;")

DispatchQueue.concurrentPerform(iterations: todo.count) { i in
    let s = todo[i]
    guard let sig = signature(of: s.url) else {
        writeQ.sync { failed += 1; processed += 1 }
        return
    }
    let lum = Double(sig.reduce(0) { $0 + Int($1) }) / Double(sig.count) / 255.0
    writeQ.sync {
        sqlite3_reset(ins)
        sqlite3_bind_text(ins, 1, s.day, -1, SQLITE_TRANSIENT)
        sqlite3_bind_text(ins, 2, s.base, -1, SQLITE_TRANSIENT)
        sqlite3_bind_int(ins, 3, Int32(s.gen))
        sqlite3_bind_double(ins, 4, s.ts)
        sqlite3_bind_text(ins, 5, s.url.path, -1, SQLITE_TRANSIENT)
        sig.withUnsafeBufferPointer { p in
            sqlite3_bind_blob(ins, 6, p.baseAddress, Int32(sig.count), SQLITE_TRANSIENT)
        }
        sqlite3_bind_double(ins, 7, lum)
        _ = sqlite3_step(ins)
        processed += 1
        if processed % 2000 == 0 {
            exec("COMMIT;"); exec("BEGIN;")
            let el = Date().timeIntervalSince(start)
            log(String(format: "%d/%d  %.0f 枚/秒  残り %.1f 分  失敗 %d",
                       processed, todo.count, Double(processed) / el,
                       Double(todo.count - processed) / (Double(processed) / el) / 60, failed))
        }
    }
}

exec("COMMIT;")
sqlite3_finalize(ins)
let el = Date().timeIntervalSince(start)
log(String(format: "完了 %d 枚 / %.1f 分 / %.0f 枚/秒 / 失敗 %d",
           processed, el / 60, Double(processed) / el, failed))
sqlite3_close(db)
