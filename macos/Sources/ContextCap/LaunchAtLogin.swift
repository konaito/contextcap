import Foundation
import ServiceManagement

/// SMAppService によるログイン時自動起動の登録・解除
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

    /// 初回起動時のデフォルト ON 登録（ユーザーが明示的に OFF にしたら再登録しない）
    static func registerOnFirstLaunch() {
        let key = "ContextCapDidDefaultRegisterLaunchAtLogin"
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: key) else { return }
        defaults.set(true, forKey: key)
        if !isEnabled {
            enable()
        }
    }
}
