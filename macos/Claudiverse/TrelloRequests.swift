import ClaudiverseCore
import SwiftUI

/**
 * A seat asking for write access to a Trello board: who, which board (a link
 * to it), why, and Approve or Deny. The server tells the seat. A request
 * decided on another device leaves the open list, and its card with it; one
 * this card lost to another device says what won.
 */
struct TrelloRequestCard: View {
    let request: TrelloWriteRequest
    @Environment(AppModel.self) private var model
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: "rectangle.stack.badge.person.crop").foregroundStyle(Theme.attention)
                Text(TrelloWording.title(request)).fontWeight(.semibold).fixedSize(horizontal: false, vertical: true)
            }
            if let reason = request.reason, !reason.isEmpty {
                Text("“\(reason)”").font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
            }
            if let url = request.boardUrl.flatMap(URL.init(string:)) {
                Link(destination: url) { Label(request.boardLabel, systemImage: "arrow.up.right.square") }.font(.callout)
            }
            Text(TrelloWording.what).font(.caption).foregroundStyle(.secondary)
            if let error { Text(error).font(.callout).foregroundStyle(Theme.bad) }
            HStack {
                Spacer()
                Button("Deny", role: .destructive) { decide(approve: false) }.disabled(busy)
                Button("Approve") { decide(approve: true) }.buttonStyle(.borderedProminent).disabled(busy)
            }
        }
        .padding(14)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.attention.opacity(0.5)))
    }

    private func decide(approve: Bool) {
        busy = true
        error = nil
        Task {
            do {
                if approve { _ = try await model.client.trello.approve(id: request.id) } else { _ = try await model.client.trello.deny(id: request.id) }
            } catch {
                self.error = TrelloWording.failure(error)
            }
            busy = false
        }
    }
}

/// The open requests, stacked: in the main window (top) and the menu bar panel.
struct TrelloRequestsStack: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if !model.trelloOpen.isEmpty {
            VStack(spacing: 8) {
                ForEach(model.trelloOpen, id: \.id) { TrelloRequestCard(request: $0) }
            }
        }
    }
}

enum TrelloWording {
    /// "pm-seat asks for write access to Example Board"
    static func title(_ r: TrelloWriteRequest) -> String { r.prompt }

    static let what = "Approving lets this seat create, move and comment on cards on this one board, until you revoke it."

    /// Why a decision did not go through: another device's decision, a request gone, or the server's words.
    static func failure(_ error: Error) -> String {
        let k = (error as NSError).userInfo["KotlinException"]
        if let lost = k as? TrelloAlreadyDecided {
            return "Already decided: \(lost.request?.status ?? "elsewhere")"
        }
        if k is TrelloRequestGone { return "This request is gone." }
        return "Not sent: \(error.message)"
    }
}
