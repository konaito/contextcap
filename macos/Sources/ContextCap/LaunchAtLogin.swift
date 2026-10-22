import Foundation
import ServiceManagement

/// SMAppService によるログイン時自動起動の登録・解除。
/// 登録はメニューの「ログイン時に起動」からだけ行う（既定はオフ）。すでに登録済みの環境はそのまま残る
enum LaunchAtLogin {
    static var isEnabled: Bool {
        SMAppService.mainApp.status == .enabled
    }

    @discardableResult
    static func enable() -> Bool {
        do {
            try SMAppService.mainApp.register()
            return true
        } catch {
            NSLog("LaunchAtLogin register failed: \(error)")
            return false
        }
    }

    @discardableResult
    static func disable() -> Bool {
        do {
            try SMAppService.mainApp.unregister()
            return true
        } catch {
            NSLog("LaunchAtLogin unregister failed: \(error)")
            return false
        }
    }
}
