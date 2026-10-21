import AppKit

/// 読み取った文字を読む窓。左から「日付」「その日の区間（または検索結果）」「全文」の 3 段。
///
/// 画像は 3 日で消えるが文字は残るので、主役は文字。画像がまだ残っている区間だけ
/// 「画像を開く」が押せる。
@MainActor
final class TextBrowserWindowController: NSWindowController, NSTableViewDataSource, NSTableViewDelegate,
    NSSearchFieldDelegate
{
    private let archive: TextArchive
    private let captureRoot: URL

    private var days: [TextArchive.Day] = []
    private var passages: [TextArchive.Passage] = []
    /// 検索中の語。空なら日付を見ている
    private var query = ""

    private let searchField = NSSearchField()
    private let statusLabel = NSTextField(labelWithString: "")
    private let dayTable = NSTableView()
    private let passageTable = NSTableView()
    private let detailHeader = NSTextField(labelWithString: "")
    private let detailText = NSTextView()
    private let openImageButton = NSButton(title: "画像を開く", target: nil, action: nil)
    private var searchDebounce: Timer?

    init(archive: TextArchive, captureRoot: URL) {
        self.archive = archive
        self.captureRoot = captureRoot
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1100, height: 700),
            styleMask: [.titled, .closable, .resizable, .miniaturizable],
            backing: .buffered, defer: false
        )
        window.title = "読み取った文字"
        window.isReleasedWhenClosed = false
        window.setFrameAutosaveName("TextBrowser")
        super.init(window: window)
        buildLayout()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    func present() {
        Self.installEditMenuIfNeeded()
        // メニューバー常駐アプリなので、前に出さないと他のアプリの裏に開く
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
        if window?.frame.origin == .zero { window?.center() }
        reloadDays()
    }

    /// ⌘C / ⌘V / ⌘A はメインメニューの項目として届く。常駐アプリはメインメニューを持たないので、
    /// 無いと検索欄に貼り付けられず、全文もコピーできない。表には出ない（.accessory のため）
    private static func installEditMenuIfNeeded() {
        guard NSApp.mainMenu == nil else { return }
        let edit = NSMenu(title: "編集")
        edit.addItem(withTitle: "取り消す", action: Selector(("undo:")), keyEquivalent: "z")
        edit.addItem(.separator())
        edit.addItem(withTitle: "カット", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "コピー", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "ペースト", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "すべてを選択", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        edit.addItem(.separator())
        edit.addItem(withTitle: "閉じる", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        let editItem = NSMenuItem(title: "編集", action: nil, keyEquivalent: "")
        editItem.submenu = edit
        let main = NSMenu()
        main.addItem(NSMenuItem())  // 先頭はアプリ名のメニューとして扱われるので空けておく
        main.addItem(editItem)
        NSApp.mainMenu = main
    }

    // MARK: - レイアウト

    private func buildLayout() {
        guard let content = window?.contentView else { return }

        searchField.placeholderString = "全期間から探す"
        searchField.delegate = self
        statusLabel.textColor = .secondaryLabelColor
        statusLabel.lineBreakMode = .byTruncatingTail

        configure(dayTable, identifier: "day", rowHeight: 24)
        configure(passageTable, identifier: "passage", rowHeight: 40)

        detailHeader.textColor = .secondaryLabelColor
        detailHeader.lineBreakMode = .byTruncatingTail
        detailText.isEditable = false
        detailText.isSelectable = true
        detailText.font = .systemFont(ofSize: 13)
        detailText.textContainerInset = NSSize(width: 8, height: 8)
        detailText.isVerticallyResizable = true
        detailText.autoresizingMask = [.width]
        let detailScroll = NSScrollView()
        detailScroll.documentView = detailText
        detailScroll.hasVerticalScroller = true
        openImageButton.target = self
        openImageButton.action = #selector(openImage)
        openImageButton.isEnabled = false

        let detailBar = NSStackView(views: [detailHeader, openImageButton])
        detailBar.orientation = .horizontal
        detailHeader.setContentHuggingPriority(.defaultLow, for: .horizontal)
        let detail = NSStackView(views: [detailBar, detailScroll])
        detail.orientation = .vertical
        detail.alignment = .leading
        detail.spacing = 6
        detailBar.widthAnchor.constraint(equalTo: detail.widthAnchor).isActive = true
        detailScroll.widthAnchor.constraint(equalTo: detail.widthAnchor).isActive = true

        let split = NSSplitView()
        split.isVertical = true
        split.dividerStyle = .thin
        split.addArrangedSubview(scrollView(for: dayTable))
        split.addArrangedSubview(scrollView(for: passageTable))
        split.addArrangedSubview(detail)
        // 窓を広げた分は全文の段に渡す。日付と一覧の幅は保つ
        split.setHoldingPriority(.defaultHigh, forSubviewAt: 0)
        split.setHoldingPriority(.defaultHigh - 1, forSubviewAt: 1)
        split.setHoldingPriority(.defaultLow, forSubviewAt: 2)

        let top = NSStackView(views: [searchField, statusLabel])
        top.orientation = .horizontal
        searchField.widthAnchor.constraint(equalToConstant: 320).isActive = true

        for view in [top, split] {
            view.translatesAutoresizingMaskIntoConstraints = false
            content.addSubview(view)
        }
        NSLayoutConstraint.activate([
            top.topAnchor.constraint(equalTo: content.topAnchor, constant: 12),
            top.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 12),
            top.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -12),
            split.topAnchor.constraint(equalTo: top.bottomAnchor, constant: 10),
            split.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            split.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            split.bottomAnchor.constraint(equalTo: content.bottomAnchor),
        ])
        split.subviews[0].widthAnchor.constraint(equalToConstant: 170).isActive = true
        split.subviews[1].widthAnchor.constraint(greaterThanOrEqualToConstant: 300).isActive = true
        let listWidth = split.subviews[1].widthAnchor.constraint(equalToConstant: 380)
        listWidth.priority = .defaultLow
        listWidth.isActive = true
        split.subviews[2].widthAnchor.constraint(greaterThanOrEqualToConstant: 320).isActive = true
    }

    private func configure(_ table: NSTableView, identifier: String, rowHeight: CGFloat) {
        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier(identifier))
        column.resizingMask = .autoresizingMask
        table.addTableColumn(column)
        table.headerView = nil
        table.rowHeight = rowHeight
        table.style = .plain
        table.columnAutoresizingStyle = .uniformColumnAutoresizingStyle
        table.dataSource = self
        table.delegate = self
    }

    private func scrollView(for table: NSTableView) -> NSScrollView {
        let scroll = NSScrollView()
        scroll.documentView = table
        scroll.hasVerticalScroller = true
        return scroll
    }

    // MARK: - 読み込み

    private func reloadDays() {
        // DB が冷えていると日の一覧に十数秒かかる。黙って空のままにしない
        if days.isEmpty { statusLabel.stringValue = "日付を集めています" }
        archive.days { [weak self] days in
            guard let self else { return }
            let selected = self.selectedDayName
            self.days = days
            self.dayTable.reloadData()
            if self.query.isEmpty {
                self.statusLabel.stringValue = days.isEmpty
                    ? "まだ何も読み取れていません"
                    : "\(days.count) 日分（\(days.last!.name)〜）。画像が消えた日も文字は残っています"
            }
            // 開き直した時は前に見ていた日、初回は最新の日を選ぶ
            let row = selected.flatMap { name in days.firstIndex { $0.name == name } } ?? 0
            if !days.isEmpty, self.query.isEmpty {
                self.dayTable.selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false)
                self.dayTable.scrollRowToVisible(row)
            }
        }
    }

    private var selectedDayName: String? {
        let row = dayTable.selectedRow
        return days.indices.contains(row) ? days[row].name : nil
    }

    private func loadDay(_ day: TextArchive.Day) {
        statusLabel.stringValue = "\(day.name) を読んでいます"
        archive.passages(day: day.name) { [weak self] passages in
            guard let self, self.query.isEmpty, self.selectedDayName == day.name else { return }
            self.show(passages)
            self.statusLabel.stringValue =
                "\(day.name): \(day.shots) 枚 → \(passages.count) 区間（同じ文字が続く間は 1 つにまとめています）"
        }
    }

    private func runSearch() {
        let text = searchField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        query = text
        if text.isEmpty {
            if let day = days.first(where: { $0.name == selectedDayName }) {
                loadDay(day)
            } else {
                reloadDays()
            }
            return
        }
        dayTable.deselectAll(nil)
        statusLabel.stringValue = "「\(text)」を探しています"
        archive.search(text) { [weak self] passages in
            guard let self, self.query == text else { return }
            self.show(passages)
            let more = passages.reduce(0) { $0 + $1.count } >= TextArchive.searchLimit ? "（新しい順に打ち切り）" : ""
            self.statusLabel.stringValue = "「\(text)」: \(passages.count) 区間\(more)"
        }
    }

    private func show(_ list: [TextArchive.Passage]) {
        passages = list
        passageTable.reloadData()
        if list.isEmpty {
            showDetail(nil)
        } else {
            passageTable.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
            passageTable.scrollRowToVisible(0)
        }
    }

    private func showDetail(_ passage: TextArchive.Passage?) {
        guard let passage else {
            detailHeader.stringValue = ""
            detailText.string = ""
            openImageButton.isEnabled = false
            return
        }
        detailHeader.stringValue = "\(passage.day)  \(timeRange(passage))"
        let attributed = NSMutableAttributedString(
            string: passage.text,
            attributes: [.font: NSFont.systemFont(ofSize: 13), .foregroundColor: NSColor.labelColor]
        )
        if !query.isEmpty {
            let ns = passage.text as NSString
            var range = NSRange(location: 0, length: ns.length)
            while true {
                let found = ns.range(of: query, options: [.caseInsensitive], range: range)
                if found.location == NSNotFound { break }
                attributed.addAttribute(.backgroundColor, value: NSColor.findHighlightColor, range: found)
                range = NSRange(location: found.upperBound, length: ns.length - found.upperBound)
            }
        }
        detailText.textStorage?.setAttributedString(attributed)
        detailText.scrollToBeginningOfDocument(nil)
        openImageButton.isEnabled = imageURL(for: passage) != nil
    }

    private func timeRange(_ p: TextArchive.Passage) -> String {
        let first = TextArchive.clockText(p.firstBase)
        guard p.count > 1 else { return first }
        // 検索結果は新しい順なので、区間の first と last が逆になる
        let (a, b) = p.firstBase < p.lastBase ? (p.firstBase, p.lastBase) : (p.lastBase, p.firstBase)
        return "\(TextArchive.clockText(a))〜\(TextArchive.clockText(b)) · \(p.count) 枚"
    }

    /// 区間の中で画像がまだ残っている 1 枚。Retention は 3 日で消すので、たいていは無い
    private func imageURL(for passage: TextArchive.Passage) -> URL? {
        let dayDir = captureRoot.appendingPathComponent(passage.day)
        let suffixes = [""] + CaptureFile.legacyGenerationSuffixes.map { ".\($0.suffix)" }
        for base in [passage.lastBase, passage.firstBase] {
            for suffix in suffixes {
                let url = dayDir.appendingPathComponent("\(base)\(suffix).jpg")
                if FileManager.default.fileExists(atPath: url.path) { return url }
            }
        }
        return nil
    }

    @objc private func openImage() {
        let row = passageTable.selectedRow
        guard passages.indices.contains(row), let url = imageURL(for: passages[row]) else { return }
        NSWorkspace.shared.open(url)
    }

    // MARK: - NSSearchFieldDelegate

    func controlTextDidChange(_ obj: Notification) {
        // 1 文字ごとに走らせない。2 文字以下は全件走査になるので特に
        searchDebounce?.invalidate()
        searchDebounce = Timer.scheduledTimer(withTimeInterval: 0.3, repeats: false) { [weak self] _ in
            Task { @MainActor [weak self] in self?.runSearch() }
        }
    }

    // MARK: - NSTableViewDataSource / Delegate

    func numberOfRows(in tableView: NSTableView) -> Int {
        tableView === dayTable ? days.count : passages.count
    }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        if tableView === dayTable {
            let day = days[row]
            return cell(tableView, id: "day", title: day.name, subtitle: nil)
        }
        let p = passages[row]
        let prefix = query.isEmpty ? "" : "\(p.day)  "
        return cell(tableView, id: "passage", title: prefix + timeRange(p), subtitle: snippet(p))
    }

    func tableViewSelectionDidChange(_ notification: Notification) {
        guard let table = notification.object as? NSTableView else { return }
        if table === dayTable {
            let row = dayTable.selectedRow
            guard days.indices.contains(row) else { return }
            if !query.isEmpty {
                // 日付を選んだら検索をやめてその日を見る
                query = ""
                searchField.stringValue = ""
            }
            loadDay(days[row])
        } else {
            let row = passageTable.selectedRow
            showDetail(passages.indices.contains(row) ? passages[row] : nil)
        }
    }

    /// 一覧に出す 1 行。検索中は語を含む行、そうでなければ最初の行
    private func snippet(_ p: TextArchive.Passage) -> String {
        let lines = p.text.split(separator: "\n", omittingEmptySubsequences: true)
        if !query.isEmpty, let hit = lines.first(where: { $0.localizedCaseInsensitiveContains(query) }) {
            return String(hit)
        }
        return lines.prefix(3).joined(separator: " / ")
    }

    private func cell(_ table: NSTableView, id: String, title: String, subtitle: String?) -> NSView {
        let identifier = NSUserInterfaceItemIdentifier(id + "Cell")
        let view = (table.makeView(withIdentifier: identifier, owner: nil) as? TwoLineCell) ?? {
            let v = TwoLineCell(twoLines: subtitle != nil)
            v.identifier = identifier
            return v
        }()
        view.title.stringValue = title
        view.subtitle.stringValue = subtitle ?? ""
        return view
    }
}

/// 一覧の 1 行。日付は 1 行、区間は「時刻」＋「中身の先頭」の 2 行
private final class TwoLineCell: NSTableCellView {
    let title = NSTextField(labelWithString: "")
    let subtitle = NSTextField(labelWithString: "")

    init(twoLines: Bool) {
        super.init(frame: .zero)
        let stack = NSStackView(views: twoLines ? [title, subtitle] : [title])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 1
        title.lineBreakMode = .byTruncatingTail
        subtitle.lineBreakMode = .byTruncatingTail
        if twoLines {
            title.font = .systemFont(ofSize: 11)
            title.textColor = .secondaryLabelColor
        }
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 6),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -6),
            stack.centerYAnchor.constraint(equalTo: centerYAnchor),
        ])
        for field in [title, subtitle] {
            field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }
}
