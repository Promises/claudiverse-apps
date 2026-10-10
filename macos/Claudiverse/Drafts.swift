import Foundation
import Observation

/**
 * What the operator has typed or attached for each seat and not sent yet.
 * A draft stays until it is sent or cleared: switching seats, closing a
 * window or quitting the app keeps it. Every view of a seat (the main
 * window, its own window, a board tile) shows the same draft.
 *
 * Saved in UserDefaults as JSON on each change; attachments as file paths.
 * Pasted images live in [pastedFolder], so a saved draft never points at a
 * file the system may clear from the temporary folder.
 */
@MainActor
@Observable
final class Drafts {
    struct Draft: Codable, Equatable {
        var text = ""
        var attachments: [String] = []

        var isEmpty: Bool { text.isEmpty && attachments.isEmpty }
    }

    private var bySeat: [Int64: Draft]
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let key: String

    init(defaults: UserDefaults = .standard, key: String = "drafts") {
        self.defaults = defaults
        self.key = key
        let saved = defaults.data(forKey: key).flatMap { try? JSONDecoder().decode([String: Draft].self, from: $0) } ?? [:]
        // Attachments whose file is gone (deleted, or a volume not mounted) are dropped rather than failing the send.
        bySeat = Dictionary(uniqueKeysWithValues: saved.compactMap { id, draft in
            guard let seat = Int64(id) else { return nil }
            var d = draft
            d.attachments = d.attachments.filter { FileManager.default.fileExists(atPath: $0) }
            return d.isEmpty ? nil : (seat, d)
        })
    }

    func text(_ seat: Int64) -> String { bySeat[seat]?.text ?? "" }

    func attachments(_ seat: Int64) -> [URL] { (bySeat[seat]?.attachments ?? []).map { URL(fileURLWithPath: $0) } }

    func setText(_ text: String, for seat: Int64) {
        update(seat) { $0.text = text }
    }

    func setAttachments(_ urls: [URL], for seat: Int64) {
        update(seat) { $0.attachments = urls.map(\.path) }
    }

    /// Sent: the draft is gone.
    func clear(_ seat: Int64) {
        guard bySeat.removeValue(forKey: seat) != nil else { return }
        save()
    }

    private func update(_ seat: Int64, _ change: (inout Draft) -> Void) {
        var d = bySeat[seat] ?? Draft()
        change(&d)
        guard d != bySeat[seat] ?? Draft() else { return }
        bySeat[seat] = d.isEmpty ? nil : d
        save()
    }

    private func save() {
        let byKey = Dictionary(uniqueKeysWithValues: bySeat.map { (String($0.key), $0.value) })
        if let data = try? JSONEncoder().encode(byKey) { defaults.set(data, forKey: key) }
    }

    /// Where pasted images are written: kept as long as a draft needs them.
    nonisolated static var pastedFolder: URL {
        AppFolder.support.appendingPathComponent("Pasted", isDirectory: true)
    }

    /// Pasted images no draft refers to, older than [days]: sent long ago, or removed from their draft.
    func removeStalePastes(olderThan days: Double = 7) {
        let fm = FileManager.default
        let inUse = Set(bySeat.values.flatMap(\.attachments))
        let cutoff = Date().addingTimeInterval(-days * 86_400)
        let files = (try? fm.contentsOfDirectory(at: Self.pastedFolder, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        for file in files where !inUse.contains(file.path) {
            let modified = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantFuture
            if modified < cutoff { try? fm.removeItem(at: file) }
        }
    }
}
