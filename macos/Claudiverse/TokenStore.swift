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
 * another bundle id (dev.claudiverse.desktop.<name>) gets "Claudiverse <name>",
 * so it keeps its own token, pasted images and harness socket, and can run
 * beside the app signed in to another server.
 */
enum AppFolder {
    static let support: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let id = Bundle.main.bundleIdentifier ?? ""
        let prefix = "dev.claudiverse.desktop."
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
