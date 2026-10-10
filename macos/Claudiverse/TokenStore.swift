import Foundation

/**
 * The super token, in a file only the operator's account can read
 * (~/Library/Application Support/Claudiverse/token, mode 0600, its folder 0700).
 *
 * Not the keychain. The operator chose a token store that never prompts and
 * that their own programs may read without asking (2026-10-04). The keychain
 * cannot give that to this app: it is signed without an Apple team, and
 * macOS ties a keychain item such an app creates to that exact build, by its
 * access list and again by its partition list, so it asked for the password
 * after every rebuild even with an any-application access list.
 */
/**
 * This app's folder in Application Support: "Claudiverse". A copy built under
 * another bundle id (tech.henning.claudiverse.<name>) gets "Claudiverse <name>",
 * so it keeps its own token, pasted images and harness socket, and can run
 * beside the app signed in to another server.
 */
enum AppFolder {
    static let support: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let id = Bundle.main.bundleIdentifier ?? ""
        let prefix = "tech.henning.claudiverse."
        let name = id.hasPrefix(prefix) && !id.hasSuffix(".tests") ? "Claudiverse \(id.dropFirst(prefix.count))" : "Claudiverse"
        return base.appendingPathComponent(name, isDirectory: true)
    }()
}

enum TokenStore {
    private static var folder: URL { AppFolder.support }

    private static var file: URL { folder.appendingPathComponent("token") }

    static func token() -> String? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        let token = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        return token.isEmpty ? nil : token
    }

    static func save(_ token: String) {
        let fm = FileManager.default
        do {
            try fm.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            try fm.setAttributes([.posixPermissions: 0o700], ofItemAtPath: folder.path)
            // Created 0600 before the token is written, so it is never readable by others, not even briefly.
            if !fm.fileExists(atPath: file.path) {
                fm.createFile(atPath: file.path, contents: nil, attributes: [.posixPermissions: 0o600])
            }
            try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
            try Data(token.utf8).write(to: file, options: [])
        } catch {
            log.error("token not saved: \(error.localizedDescription, privacy: .public)")
        }
    }

    static func delete() {
        try? FileManager.default.removeItem(at: file)
    }
}

/**
 * The app's preferences (server address, drafts, queued messages, board
 * layouts, ssh targets) are kept per bundle id. Before the id became
 * tech.henning.claudiverse it was dev.claudiverse.desktop: on the first start
 * under the new id, its preferences are copied over once, so an update keeps
 * them. The token file needs nothing: its folder is named for the app, not
 * the id.
 */
enum Preferences {
    static let oldBundleId = "dev.claudiverse.desktop"

    /// Only the app itself: a copy under another id keeps its own preferences.
    static func adoptOldBundleId() {
        guard Bundle.main.bundleIdentifier == "tech.henning.claudiverse" else { return }
        if copy(from: oldBundleId, to: "tech.henning.claudiverse") {
            log.info("preferences copied from \(oldBundleId, privacy: .public)")
        }
    }

    /// Copies domain [old] into [new] when [new] has nothing yet and [old] has something. True when it copied.
    @discardableResult
    static func copy(from old: String, to new: String, _ defaults: PreferenceDomains = UserDefaults.standard) -> Bool {
        guard defaults.persistentDomain(forName: new)?.isEmpty ?? true,
              let values = defaults.persistentDomain(forName: old), !values.isEmpty else { return false }
        defaults.setPersistentDomain(values, forName: new)
        return true
    }
}

/// The two calls the copy needs: UserDefaults, or a stand-in that writes no files.
protocol PreferenceDomains {
    func persistentDomain(forName domainName: String) -> [String: Any]?
    func setPersistentDomain(_ domain: [String: Any], forName domainName: String)
}

extension UserDefaults: PreferenceDomains {}
