import XCTest
@testable import Claudiverse

/// A seat's identity and login never reach what the app starts: its own environment, the processes it runs, the terminal it opens.
final class CleanEnvironmentTests: XCTestCase {
    /// A seat's variables, with made-up values.
    private let seat = [
        "CLAUDIVERSE_TITLE": "lorem-seat", "CLAUDIVERSE_TOKEN": "ipsum-token", "CLAUDIVERSE_URL": "http://server:4010",
        "ANTHROPIC_BASE_URL": "http://proxy:8080", "ANTHROPIC_AUTH_TOKEN": "dolor", "CLAUDE_CODE_OAUTH_TOKEN": "sit",
        "CLAUDE_CONFIG_DIR": "/tmp/lorem-config",
    ]
    /// Look alike, but are not a seat's: they stay.
    private let kept = ["ANTHROPIC_MODEL": "lorem", "CLAUDE_CODE_EFFORT": "high", "MY_CLAUDIVERSE_NOTE": "x"]

    override func tearDown() {
        for name in seat.keys where name.hasPrefix("CLAUDIVERSE_") || CleanEnvironment.names.contains(name) { unsetenv(name) }
        for name in kept.keys { unsetenv(name) }
        super.tearDown()
    }

    /// The variable names `env` prints when run by [process].
    private func names(_ process: Process) throws -> Set<String> {
        let out = Pipe()
        process.standardOutput = out
        process.standardError = FileHandle.nullDevice
        try process.run()
        let data = out.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        XCTAssertEqual(process.terminationStatus, 0)
        return Set(String(decoding: data, as: UTF8.self).split(separator: "\n").compactMap { $0.split(separator: "=").first.map(String.init) })
    }

    func testOnlyTheSeatsVariablesAreDropped() {
        let all = seat.merging(kept) { a, _ in a }.merging(["PATH": "/usr/bin", "HOME": "/var/empty"]) { a, _ in a }
        XCTAssertEqual(Set(CleanEnvironment.scrubbed(all).keys), Set(kept.keys).union(["PATH", "HOME"]))
    }

    /// A process the app runs never sees them, whatever the app's own environment holds.
    func testAProcessItRunsGetsAScrubbedEnvironment() throws {
        for (k, v) in seat.merging(kept, uniquingKeysWith: { a, _ in a }) { setenv(k, v, 1) }
        // Control: a process started the plain way inherits them; this is what reached seats.
        XCTAssertTrue(Set(seat.keys).isSubset(of: try names({ let p = Process(); p.executableURL = URL(fileURLWithPath: "/usr/bin/env"); return p }())))
        let seen = try names(CleanEnvironment.process("/usr/bin/env", []))
        XCTAssertTrue(seen.isDisjoint(with: seat.keys), "leaked: \(seen.intersection(seat.keys))")
        XCTAssertTrue(Set(kept.keys).union(["HOME", "PATH"]).isSubset(of: seen), "the rest of the environment stays")
    }

    /// At launch the app drops them from itself, so even what it starts some other way (iTerm, by AppleScript) has none.
    func testTheAppDropsThemFromItself() {
        for (k, v) in seat.merging(kept, uniquingKeysWith: { a, _ in a }) { setenv(k, v, 1) }
        XCTAssertNotNil(getenv("CLAUDIVERSE_TITLE"), "control: set")
        CleanEnvironment.scrubThisProcess()
        for name in seat.keys { XCTAssertNil(getenv(name), name) }
        for name in kept.keys { XCTAssertNotNil(getenv(name), name) }
    }

    /// The line typed into a new terminal unsets them in that shell, in each shell it may be; a value cannot add a command.
    func testTheTerminalUnsetsThemInItsShell() throws {
        let marker = FileManager.default.temporaryDirectory.appendingPathComponent("lorem-\(UUID().uuidString)")
        var env = seat.merging(kept) { a, _ in a }
        env["PATH"] = "/usr/bin:/bin"
        env["CLAUDIVERSE_PROFILE"] = "x\nCLAUDIVERSE_EVIL=$(touch \(marker.path))\n`touch \(marker.path)`"
        let shells = [("/bin/zsh", ["-f", "-c"]), ("/bin/bash", ["--noprofile", "--norc", "-c"]), ("/bin/sh", ["-c"])]
        for (shell, flags) in shells {
            func run(_ line: String) throws -> Set<String> {
                let p = Process()
                p.executableURL = URL(fileURLWithPath: shell)
                p.arguments = flags + [line]
                p.environment = env
                return try names(p)
            }
            XCTAssertTrue(Set(seat.keys).isSubset(of: try run("env")), "control: \(shell) has them without the line")
            let seen = try run(TerminalOpener.typed("env"))
            XCTAssertTrue(seen.isDisjoint(with: Set(seat.keys).union(["CLAUDIVERSE_PROFILE"])), "\(shell) kept \(seen.intersection(seat.keys))")
            XCTAssertTrue(Set(kept.keys).isSubset(of: seen), "\(shell) dropped the rest")
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: marker.path), "a value ran as a command")
    }
}
