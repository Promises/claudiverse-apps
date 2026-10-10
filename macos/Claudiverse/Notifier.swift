import AppKit
import ClaudiverseCore
import Foundation
import UserNotifications

/**
 * A notification when a seat asks for the operator, once per request: a
 * request the operator has already been told about is not told again, and
 * one that is resolved takes its notification with it. Clicking one opens
 * the seat.
 */
@MainActor
@Observable
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    @ObservationIgnored var onOpen: ((Int64) -> Void)?
    /// False when macOS has notifications for this app switched off; nil until known.
    private(set) var allowed: Bool?
    /// What each seat was last notified for: its request id and when the request last changed.
    @ObservationIgnored private var told: [Int64: String] = [:]
    @ObservationIgnored private var first = true

    override init() {
        super.init()
        UNUserNotificationCenter.current().delegate = self
        Self.installSound()
    }

    func requestPermission() {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, error in
            center.getNotificationSettings { settings in
                log.notice("notifications: granted=\(granted) authorization=\(settings.authorizationStatus.rawValue) alerts=\(settings.alertSetting.rawValue) error=\(error?.localizedDescription ?? "none", privacy: .public)")
                let on = settings.authorizationStatus == .authorized && settings.alertSetting == .enabled
                Task { @MainActor in self?.allowed = on }
            }
        }
    }

    /// Read the setting again: the operator may have changed it in System Settings meanwhile.
    func recheck() {
        UNUserNotificationCenter.current().getNotificationSettings { [weak self] settings in
            let on = settings.authorizationStatus == .authorized && settings.alertSetting == .enabled
            Task { @MainActor in self?.allowed = on }
        }
    }

    /// System Settings › Notifications, at this app.
    static func openSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=dev.claudiverse.desktop") {
            NSWorkspace.shared.open(url)
        }
    }

    /// The open requests now. The first read only records them: requests already open at launch were seen elsewhere.
    func update(_ open: [Int64: HumanRequest]) {
        let center = UNUserNotificationCenter.current()
        for (seat, request) in open {
            let stamp = "\(request.id)@\(request.updatedAt ?? "")"
            if told[seat] == stamp { continue }
            told[seat] = stamp
            if first { continue }
            let content = UNMutableNotificationContent()
            content.title = request.seatTitle
            content.body = request.summary
            content.sound = Self.sound
            content.userInfo = ["seat": seat]
            center.add(UNNotificationRequest(identifier: id(seat), content: content, trigger: nil)) { error in
                log.notice("notification for seat \(seat): \(error?.localizedDescription ?? "posted", privacy: .public)")
            }
        }
        let gone = told.keys.filter { open[$0] == nil }
        gone.forEach { told[$0] = nil }
        center.removeDeliveredNotifications(withIdentifiers: gone.map(id))
        first = false
        NSApplication.shared.dockTile.badgeLabel = open.isEmpty ? nil : "\(open.count)"
    }

    /// The poke's name in ~/Library/Sounds: the app's own, so it cannot clash with another app's sound.
    private static let soundFile = "Claudiverse Poke.caf"

    /// "You were poked": a seat asking for the operator. See [installSound].
    static let sound = UNNotificationSound(named: UNNotificationSoundName(soundFile))

    /**
     * Put the poke where Notification Center finds it. It looks a sound up
     * among the system's and the user's sounds (~/Library/Sounds), not in the
     * app's bundle: from the bundle alone it logged "not in the collections"
     * and played the default sound. Copied from the bundle (Sounds/poke.caf;
     * CAF, since a notification sound cannot be MP3) when missing or changed.
     */
    static func installSound() {
        guard let source = Bundle.main.url(forResource: "poke", withExtension: "caf") else { return }
        let fm = FileManager.default
        let folder = fm.homeDirectoryForCurrentUser.appendingPathComponent("Library/Sounds", isDirectory: true)
        let target = folder.appendingPathComponent(soundFile)
        if fm.contentsEqual(atPath: source.path, andPath: target.path) { return }
        do {
            try fm.createDirectory(at: folder, withIntermediateDirectories: true)
            if fm.fileExists(atPath: target.path) { try fm.removeItem(at: target) }
            try fm.copyItem(at: source, to: target)
        } catch {
            log.error("notification sound not installed: \(error.localizedDescription, privacy: .public)")
        }
    }

    private func id(_ seat: Int64) -> String { "seat-\(seat)" }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let seat = response.notification.request.content.userInfo["seat"] as? Int64 else { return }
        await MainActor.run { onOpen?(seat) }
    }

    /// Shown even while the app is in front: the seat may not be the one on screen.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}
