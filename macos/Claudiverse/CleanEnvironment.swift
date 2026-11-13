import Foundation

/**
 * Keeps a seat's identity out of everything this app starts.
 *
 * Opened from a seat's shell, the app inherits that seat's CLAUDIVERSE_*
 * variables and its API login (ANTHROPIC_BASE_URL, ANTHROPIC_AUTH_TOKEN,
 * CLAUDE_CODE_OAUTH_TOKEN, CLAUDE_CONFIG_DIR), and so does every process it
 * starts. That includes iTerm, when the app's AppleScript is what launches
 * it. A Claude Code later started from such a terminal registers as that
 * seat, takes over its inbox, and has no login of its own.
 *
 * So, whatever the app's own environment holds:
 * - the app drops these variables from its own process as it starts, so
 *   anything it launches cannot inherit them;
 * - each process it runs gets a scrubbed environment of its own;
 * - a terminal it opens unsets them before running its command. That covers
 *   an iTerm that was already running with them, whose environment this app
 *   cannot see.
 */
enum CleanEnvironment {
    /// The API login a seat is started with.
    static let names: Set<String> = ["ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CONFIG_DIR"]
    /// A seat's identity: its title, token, fleet, profile and so on.
    static let prefix = "CLAUDIVERSE_"

    static func isSeatOnly(_ name: String) -> Bool { name.hasPrefix(prefix) || names.contains(name) }

    static func scrubbed(_ environment: [String: String]) -> [String: String] {
        environment.filter { !isSeatOnly($0.key) }
    }

    /// Drops them from this process. Called first thing at launch, before anything is started.
    static func scrubThisProcess() {
        for name in ProcessInfo.processInfo.environment.keys where isSeatOnly(name) { unsetenv(name) }
    }

    /// A process for [executable] with this app's environment, scrubbed.
    static func process(_ executable: String, _ arguments: [String]) -> Process {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: executable)
        p.arguments = arguments
        p.environment = scrubbed(ProcessInfo.processInfo.environment)
        return p
    }

    /// A shell line (zsh, bash or sh) that unsets them all in the shell it is typed into. The CLAUDIVERSE_ names are found
    /// at run time; only identifier characters are taken from `env`, so a variable's value cannot add a command.
    static var unsetLine: String {
        "unset \(names.sorted().joined(separator: " ")) $(env | sed -nE 's/^(\(prefix)[A-Za-z0-9_]*)=.*/\\1/p')"
    }
}
