import AppKit

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var statusItem: NSStatusItem!
    private var stats: StatsStore!
    private var capture: CaptureManager!
    private var indexer: OCRIndexer!
    private var retention: Retention!
    private var keyLog: KeyLog!
    private var textArchive: TextArchive!
    private var textBrowser: TextBrowserWindowController?
    private var retentionTimer: Timer?
    private var permissionRetryTimer: Timer?
    private var sweeping = false
    private var lastSweep: RetentionResult?

    /// 保存先。`defaults write app.imichat.contextcap CaptureRoot <path>` で
    /// 上書き可能（テスト用）。未設定なら ~/ContextCap
    private static let captureRoot: URL = {
        if let path = UserDefaults.standard.string(forKey: "CaptureRoot"), !path.isEmpty {
            return URL(fileURLWithPath: (path as NSString).expandingTildeInPath, isDirectory: true)
        }
        return FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("ContextCap")
    }()

    func applicationDidFinishLaunching(_ notification: Notification) {
        try? FileManager.default.createDirectory(
            at: Self.captureRoot, withIntermediateDirectories: true
        )

        stats = StatsStore(rootDirectory: Self.captureRoot)
        indexer = OCRIndexer()
        indexer.onStuck = { [weak self] in
            Task { @MainActor [weak self] in self?.relaunchForStuckOCR() }
        }
        capture = CaptureManager(stats: stats, indexer: indexer)
        retention = Retention(root: Self.captureRoot)
        keyLog = KeyLog(root: Self.captureRoot)
        textArchive = TextArchive()
        // 日の一覧は DB が冷えていると十数秒かかる。メニューを開く前に集計しておく
        textArchive.refreshSpan()

        // アプリが落ちていた間の撮影や、enqueue の取りこぼしを拾い直す。
        // これは backlog キューに入るので、新規撮影の OCR を待たせない
        indexer.reconcile(root: Self.captureRoot)

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        if let button = statusItem.button {
            button.image = NSImage(
                systemSymbolName: "camera.fill",
                accessibilityDescription: "ContextCap"
            )
        }

        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu

        // ログイン項目には勝手に登録しない。メニューの「ログイン時に起動」で利用者が選ぶ
        // （以前は初回起動で無条件に登録していた。App Review Guidelines 2.4.5(iii) にも反する）
        capture.start()
        keyLog.start()
        schedulePermissionRetryIfNeeded()

        // 保持期間・容量のチェックは 5 分ごと。撮影ごとにやる必要はない
        // （削除は日単位の話で、5 秒ごとに判断が変わるものではない）
        sweepRetention()
        retentionTimer = Timer.scheduledTimer(withTimeInterval: 300, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.sweepRetention()
            }
        }
    }

    // メニューを開くたびに最新の統計で組み直す
    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()

        let stateText: String
        switch capture.state {
        case .running: stateText = "記録中（5秒間隔）"
        case .paused: stateText = "一時停止中"
        case .noPermission: stateText = "画面収録の権限がありません"
        }
        menu.addItem(disabledItem(stateText))
        if let error = capture.lastError {
            menu.addItem(disabledItem("直近のエラー: \(error)"))
        }

        menu.addItem(.separator())
        menu.addItem(disabledItem("累計: \(stats.countText)"))
        menu.addItem(disabledItem("画像の期間: \(stats.durationText)"))
        // 画像は保持期間で消えるが、読み取った文字は消さない。別に数える
        menu.addItem(disabledItem(textArchive.spanText))
        textArchive.refreshSpan()
        menu.addItem(disabledItem("容量: \(stats.sizeText) / \(StorageBudget.displayText)"))
        menu.addItem(disabledItem("撮影画質: \(capture.profileText)"))
        menu.addItem(disabledItem("保持: \(Retention.retentionDays) 日（OCR 済みのみ削除）"))
        menu.addItem(disabledItem(indexer.statusText))
        menu.addItem(disabledItem("キー記録: \(keyLogStatusText)"))
        if let last = lastSweep {
            var line = "前回の削除: \(last.deleted) 枚"
            // 消せなかった分は必ず見せる。黙って溜まると容量だけ増えて原因が分からない
            if last.blockedByOCR > 0 {
                line += "・未 OCR で保留 \(last.blockedByOCR) 枚"
            }
            menu.addItem(disabledItem(line))
        }
        menu.addItem(.separator())

        switch capture.state {
        case .running:
            menu.addItem(actionItem("一時停止", #selector(togglePause)))
        case .paused:
            menu.addItem(actionItem("再開", #selector(togglePause)))
        case .noPermission:
            menu.addItem(actionItem("システム設定で権限を許可…", #selector(openPrivacySettings)))
        }

        // 一時停止中は止めているだけなので、権限の案内は出さない
        if !keyLog.isActive && capture.state != .paused {
            menu.addItem(actionItem("キー記録を許可（アクセシビリティ）…", #selector(openAccessibilitySettings)))
        }

        menu.addItem(actionItem("読み取った文字を開く…", #selector(openTextBrowser)))
        menu.addItem(actionItem("保存先を Finder で開く", #selector(openFolder)))

        let loginItem = actionItem("ログイン時に起動", #selector(toggleLaunchAtLogin))
        loginItem.state = LaunchAtLogin.isEnabled ? .on : .off
        menu.addItem(loginItem)

        menu.addItem(.separator())
        menu.addItem(actionItem("統計を再スキャン", #selector(rescanStats)))
        menu.addItem(actionItem("ContextCap を終了", #selector(quit)))
    }

    /// 権限が無い間は 10 秒ごとに再チェックし、付与されたら自動で撮影を始める
    private func schedulePermissionRetryIfNeeded() {
        guard capture.state == .noPermission, permissionRetryTimer == nil else { return }
        permissionRetryTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                guard self.capture.hasPermission() else { return }
                self.permissionRetryTimer?.invalidate()
                self.permissionRetryTimer = nil
                self.capture.start()
            }
        }
    }

    // MARK: - Retention

    /// 保持期間を過ぎた「OCR 済み」画像を消す。再圧縮はしない。
    private func sweepRetention() {
        guard !sweeping else { return }
        sweeping = true
        let retention = self.retention!
        let indexer = self.indexer!
        Task.detached(priority: .utility) {
            // OCR 済みキーの取得は indexer のキュー上で走る。OCR 中なら待たされるが、
            // 削除は 5 分に 1 回でよいので急がない
            let keys = indexer.indexedKeys()
            let result = await retention.sweep(indexedKeys: keys)
            await MainActor.run { [weak self] in
                guard let self else { return }
                self.sweeping = false
                guard let result else { return }
                self.lastSweep = result
                self.stats.rescan()
            }
        }
    }

    // MARK: - Actions

    /// 一時停止は撮影とキー記録の両方を止める。以前は撮影だけ止まり、キー記録は続いていた
    @objc private func togglePause() {
        if capture.state == .running {
            capture.pause()
            keyLog.stop()
        } else {
            capture.start()
            keyLog.start()
        }
    }

    private var keyLogStatusText: String {
        if keyLog.isActive { return "有効" }
        if capture.state == .paused { return "一時停止中" }
        return "アクセシビリティ権限が必要"
    }

    @objc private func openTextBrowser() {
        if textBrowser == nil {
            textBrowser = TextBrowserWindowController(archive: textArchive, captureRoot: Self.captureRoot)
        }
        textBrowser?.present()
    }

    @objc private func openFolder() {
        NSWorkspace.shared.open(Self.captureRoot)
    }

    @objc private func toggleLaunchAtLogin() {
        if LaunchAtLogin.isEnabled {
            LaunchAtLogin.disable()
        } else {
            LaunchAtLogin.enable()
        }
    }

    @objc private func openPrivacySettings() {
        let url = URL(
            string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture"
        )!
        NSWorkspace.shared.open(url)
    }

    @objc private func openAccessibilitySettings() {
        let url = URL(
            string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"
        )!
        NSWorkspace.shared.open(url)
    }

    @objc private func rescanStats() {
        stats.rescan()
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }

    /// 終了を gaps.jsonl に残す。これが無いと、落ちた時と自分で終了した時が
    /// 解析側で区別できない（どちらも「その先に何も無い」になる）
    /// 撮影直後の OCR が続けて失敗した時に、アプリを起動し直す。
    /// Vision がプロセス内で壊れた状態に入ると、再起動するまで全件が失敗し続ける
    /// （`OCRFailureStreak` の説明を参照）。起動し直すと reconcile が failed の行を読み直す。
    /// 1 時間に 1 回まで（再起動しても直らない場合に繰り返さない）
    private func relaunchForStuckOCR() {
        let key = "OCRStuckRelaunchAt"
        let defaults = UserDefaults.standard
        let last = defaults.object(forKey: key) as? Date
        guard OCRRelaunchPolicy.shouldRelaunch(now: Date(), last: last, minInterval: 3600) else {
            NSLog("OCR: 失敗が続いているが、前回の再起動から 1 時間経っていないので見送る")
            return
        }
        defaults.set(Date(), forKey: key)
        NSLog("OCR: 失敗が続いたのでアプリを起動し直す")
        // 自分が終わってから開き直す。子プロセスは親が終了しても残る
        let relauncher = Process()
        relauncher.executableURL = URL(fileURLWithPath: "/bin/sh")
        relauncher.arguments = ["-c", "sleep 2; /usr/bin/open \"$0\"", Bundle.main.bundlePath]
        do {
            try relauncher.run()
        } catch {
            NSLog("OCR: 起動し直せなかった: \(error)")
            return
        }
        NSApp.terminate(nil)
    }

    func applicationWillTerminate(_ notification: Notification) {
        capture.recordStop()
    }

    // MARK: - Helpers

    private func disabledItem(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    private func actionItem(_ title: String, _ action: Selector) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        return item
    }
}
