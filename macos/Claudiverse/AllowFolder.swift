import ClaudiverseCore
import SwiftUI

/**
 * Allow a folder on a machine, picked as a breadcrumb of its path: each
 * segment selects that level, so a seat in
 * /Users/me/Documents/projects/game/host can allow just "host" or all of
 * "Documents". The whole disk is not offered. The machine decides: its agent
 * accepts only when its config says "allow_remote_roots": true. Allowed
 * folders inside the one chosen are removed: the chosen one covers them.
 */
struct AllowFolderView: View {
    let host: ClaudiverseCore.Host
    let folder: String
    /// After the machine accepted the folder.
    let onAllowed: () -> Void
    @Environment(AppModel.self) private var model
    /// How many path segments the folder to allow keeps; the whole path at first.
    @State private var depth: Int
    @State private var busy = false
    @State private var error: String?

    init(host: ClaudiverseCore.Host, folder: String, onAllowed: @escaping () -> Void) {
        self.host = host
        self.folder = folder
        self.onAllowed = onAllowed
        _depth = State(initialValue: Self.segments(folder).count)
    }

    static func segments(_ path: String) -> [String] { path.split(separator: "/").map(String.init) }

    /// The folder [depth] segments deep: "/Users/me/Documents" for 3.
    static func prefix(_ path: String, _ depth: Int) -> String { "/" + segments(path).prefix(depth).joined(separator: "/") }

    private var chosen: String { Self.prefix(folder, depth) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Allow a folder on \(host.name) so it can start there. Pick how much:").font(.callout)
            ScrollView(.horizontal) {
                HStack(spacing: 2) {
                    ForEach(Array(Self.segments(folder).enumerated()), id: \.offset) { i, name in
                        if i > 0 { Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.secondary) }
                        Button(name) { depth = i + 1 }
                            .buttonStyle(.plain)
                            .padding(.horizontal, 6).padding(.vertical, 3)
                            .background(i < depth ? Theme.accent.opacity(i + 1 == depth ? 0.35 : 0.15) : Color.clear, in: RoundedRectangle(cornerRadius: 5))
                            .fontWeight(i + 1 == depth ? .semibold : .regular)
                            .help(Self.prefix(folder, i + 1))
                    }
                }
            }
            .scrollIndicators(.never)
            HStack {
                Button(busy ? "Allowing…" : "Allow \(chosen)") { Task { await allow() } }.disabled(busy)
                if depth <= 2 {
                    Text("That allows seats almost anywhere in your home folder.").font(.caption).foregroundStyle(Theme.attention)
                }
            }
            if !host.remoteRoots {
                Text("\(host.name) doesn't accept folders from the app yet: set \"allow_remote_roots\": true in its host agent's config.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let error { Text(error).font(.caption).foregroundStyle(Theme.bad) }
        }
    }

    private func allow() async {
        busy = true
        defer { busy = false }
        do {
            // Without duplicates: a folder already covered adds nothing, and folders inside this one go.
            _ = try await model.client.api.allowFolder(host: host.name, path: chosen, current: host.roots)
            error = nil
            onAllowed()
        } catch {
            self.error = switch error.httpStatus {
            case 403: "Not allowed: \(host.name)'s agent doesn't accept folders from the app (\"allow_remote_roots\" is off in its config)."
            case 404: "Not allowed: \(chosen) doesn't exist on \(host.name)."
            case 504: "Not allowed: \(host.name) is offline."
            default: "Not allowed: \(error.message)"
            }
        }
    }
}
