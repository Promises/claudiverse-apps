import ClaudiverseCore
import SwiftUI

/**
 * A Claude Code seat's model in New seat, from the server's list (GET /api/models):
 * - the machine's own default first;
 * - one section per family, Z.ai's GLM among them, each family's older models
 *   folded into a submenu;
 * - a 1M switch where the model takes `[1m]` (those with it in their id
 *   already run 1M).
 * Only models the machine's Claude Code knows are offered. The id sent (with
 * `[1m]` when asked) is the session's model, and the menu reads its choice
 * back from that.
 */
struct SpawnModelPicker: View {
    let host: ClaudiverseCore.Host
    let mode: String
    let sent: String?
    let groups: [ModelGroup]
    let set: (String?) -> Void

    static let glmAutoHint = "The server starts a GLM seat in accept-edits mode when auto is asked."

    var body: some View {
        let offers = host.spawn?.offersModel == true
        let pick = SpawnModelPick.of(sent: offers ? sent : nil, in: groups)
        LabeledContent("Model") {
            Menu {
                item("Machine's default", chosen: pick.choice == nil) { set(nil) }
                ForEach(groups, id: \.id) { g in
                    Section(g.label) {
                        ForEach(g.models, id: \.id) { m in
                            item(m.label, chosen: pick.choice?.id == m.id) { set(spawnModelId(choice: m, oneM: pick.oneM)) }
                        }
                        if !g.older.isEmpty {
                            Menu("Older \(g.label)") {
                                ForEach(g.older, id: \.id) { m in
                                    item(m.label, chosen: pick.choice?.id == m.id) { set(spawnModelId(choice: m, oneM: pick.oneM)) }
                                }
                            }
                        }
                    }
                }
            } label: {
                Text(pick.choice?.label ?? "Machine's default")
            }
            .fixedSize()
            .disabled(!offers)
        }
        if let choice = pick.choice, choice.spawnOneM {
            Toggle("1M context", isOn: Binding(get: { pick.oneM }, set: { set(spawnModelId(choice: choice, oneM: $0)) }))
        }
        if let hint = SpawnModelPick.hint(machine: host.name, offers: offers, choice: pick.choice, mode: mode) {
            Text(hint).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func item(_ label: String, chosen: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            if chosen { Label(label, systemImage: "checkmark") } else { Text(label) }
        }
    }
}

/// The choice a sent model id stands for, and what to say under the picker.
enum SpawnModelPick {
    static let oneMSuffix = "[1m]"

    /// [sent] ("claude-opus-4-6[1m]", say) as a model in [groups] and whether 1M was asked; nil for the machine's default
    /// or an id not in [groups]. (Changing machine clears the session's model, so a picked id is always one offered.)
    static func of(sent: String?, in groups: [ModelGroup]) -> (choice: ModelChoice?, oneM: Bool) {
        guard let sent else { return (nil, false) }
        let oneM = sent.hasSuffix(oneMSuffix)
        let id = oneM ? String(sent.dropLast(oneMSuffix.count)) : sent
        let choice = groups.lazy.flatMap { $0.models + $0.older }.first { $0.id == id }
        return (choice, choice != nil && oneM)
    }

    static func hint(machine: String, offers: Bool, choice: ModelChoice?, mode: String) -> String? {
        if !offers {
            return "\(machine)'s host agent is older than this form: the model is its own default. Run the installer again on \(machine) to choose it here."
        }
        if choice?.family == ModelCatalog.companion.GLM && mode == "auto" { return SpawnModelPicker.glmAutoHint }
        return nil
    }
}
