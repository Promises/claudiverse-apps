import ClaudiverseCore
import Foundation

/**
 * Opens a seat's terminal in iTerm: its tmux session, named after the seat,
 * on its machine. On this Mac directly; on another machine over ssh, to the
 * target set for it in Settings › Machines (the server does not know how this
 * Mac reaches a machine). A seat started straight in a terminal has no tmux
 * session to attach to, and says so.
 *
 * iTerm is driven by AppleScript: macOS asks once to let Claudiverse control
 * it, and again after an update, since the app is signed without a team.
 */
enum TerminalOpener {
    enum Failure: LocalizedError {
        case noMachine, noSshTarget(String), noSession(String), iTerm(String)

        var errorDescription: String? {
            switch self {
            case .noMachine: "This seat has not reported its machine."
            case .noSshTarget(let host): "Set how to reach \(host) over ssh in Settings › Machines."
            case .noSession(let title): "No tmux session \"\(title)\" on this Mac: the seat was started in a terminal of its own."
            case .iTerm(let why): "iTerm did not open: \(why)"
            }
        }
    }

    /// This Mac's name as host agents report it (e.g. my-mac.local).
    static func isThisMac(_ host: String) -> Bool {
        let machine = host.lowercased()
        let mine = ProcessInfo.processInfo.hostName.lowercased()
        return machine == mine || machine.hasPrefix(mine + ".") || mine.hasPrefix(machine + ".")
    }

    static func sshTarget(for host: String) -> String? {
        UserDefaults.standard.string(forKey: "ssh.\(host)")?.trimmingCharacters(in: .whitespaces).nilIfEmpty
    }

    static func setSshTarget(_ target: String, for host: String) {
        UserDefaults.standard.set(target.trimmingCharacters(in: .whitespaces), forKey: "ssh.\(host)")
    }

    /// The command that attaches to [seat]'s session, as typed into a shell on this Mac.
    static func command(for seat: Seat) throws -> String {
        guard let host = seat.host else { throw Failure.noMachine }
        // "=" asks tmux for this exact session name, not one that merely starts with it.
        let attach = "tmux attach -t \(quote("=" + seat.title))"
        if isThisMac(host) {
            guard hasLocalSession(seat.title) else { throw Failure.noSession(seat.title) }
            return attach
        }
        guard let target = sshTarget(for: host) else { throw Failure.noSshTarget(host) }
        // A login shell there: a non-interactive ssh command gets a minimal PATH, often without tmux.
        return "ssh -t \(quote(target)) \(quote("exec $SHELL -lc " + quote(attach)))"
    }

    /// Off the main thread: the first time, macOS holds the script until the operator answers its permission dialog.
    static func open(_ seat: Seat) async throws {
        let cmd = try command(for: seat)
        try await Task.detached { try run(cmd) }.value
    }

    private static func run(_ cmd: String) throws {
        let script = """
        tell application "iTerm"
            activate
            set w to (create window with default profile)
            tell current session of w to write text "\(appleScriptEscaped(cmd))"
        end tell
        """
        var error: NSDictionary?
        NSAppleScript(source: script)?.executeAndReturnError(&error)
        if let error { throw Failure.iTerm(error[NSAppleScript.errorMessage] as? String ?? "\(error)") }
    }

    private static func hasLocalSession(_ title: String) -> Bool {
        let tmux = ["/opt/homebrew/bin/tmux", "/usr/local/bin/tmux", "/usr/bin/tmux"].first { FileManager.default.isExecutableFile(atPath: $0) }
        guard let tmux else { return false }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: tmux)
        p.arguments = ["has-session", "-t", "=" + title]
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        do { try p.run() } catch { return false }
        p.waitUntilExit()
        return p.terminationStatus == 0
    }

    /// A single-quoted shell word.
    static func quote(_ s: String) -> String { "'" + s.replacingOccurrences(of: "'", with: "'\\''") + "'" }

    private static func appleScriptEscaped(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
    }
}
