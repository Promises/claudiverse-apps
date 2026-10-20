import AppKit
import ClaudiverseCore
import Foundation
import Observation
import os

let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "tech.henning.claudiverse", category: "app")

/// The server URL and token, read by the core on its own threads on every call.
final class AccessBox: @unchecked Sendable {
    private let lock = NSLock()
    private var current = ServerAccess(baseUrl: "", token: "")

    var value: ServerAccess { lock.withLock { current } }

    func set(baseUrl: String, token: String) {
        lock.withLock { current = ServerAccess(baseUrl: baseUrl, token: token) }
    }
}

/// The outbox's queue in UserDefaults: messages typed while their seat was busy survive a quit.
final class DefaultsOutboxStore: NSObject, OutboxStore {
    private let key = "outbox"

    func load() -> String? { UserDefaults.standard.string(forKey: key) }

    func save(json: String) { UserDefaults.standard.set(json, forKey: key) }
}

extension Error {
    /// The core's ApiException behind a thrown error, when it is one.
    var apiException: ApiException? { (self as NSError).userInfo["KotlinException"] as? ApiException }

    var httpStatus: Int? { apiException?.httpStatus.map { Int(truncating: $0) } }

    /// The server's own words when it sent some, else the error's.
    var message: String { apiException?.message ?? localizedDescription }
}

/// Milliseconds since 1970, as the core counts time.
var nowMillis: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

/**
 * The app's state for as long as it runs: who is signed in, the seat list, and
 * the seats asking for the operator. One per process.
 */
@MainActor
@Observable
final class AppModel {
    enum Phase { case starting, signedOut, signedIn }

    private(set) var phase: Phase = .starting
    var serverURL: String = UserDefaults.standard.string(forKey: "serverURL") ?? "" {
        didSet { UserDefaults.standard.set(serverURL, forKey: "serverURL") }
    }
    private(set) var email: String?
    /// The sign-in's scope ("super" for this Mac's own sign-in), nil until the server has said.
    private(set) var scope: String?

    /// May change app-only settings (a seat's accept-all): an app or super sign-in. Unknown counts as yes; the server
    /// refuses a change anyway when it may not.
    var canChangeSettings: Bool { scope.map { Me(email: email ?? "", scope: $0).canChangeSettings } ?? true }

    private(set) var seats: [Seat] = []
    private(set) var seatsError: String?
    /// The seat list's Running filter is off: stopped seats are fetched too, only then.
    var includeStopped = false {
        didSet { Task { await refreshSeats() } }
    }

    /// Open requests from seats asking for the operator, by seat id.
    private(set) var needsYou: [Int64: HumanRequest] = [:]
    private(set) var spawnNotice: SpawnNotice?
    /// Messages waiting for their seat to go idle, all seats.
    private(set) var queued: [QueuedMessage] = []
    /// The fleets with their live runner spaces, from cv-runner's heartbeat.
    private(set) var fleets: [Fleet] = []
    /// Seat restarts since sign-in, oldest first, and each batch's latest state (core's Restarts).
    private(set) var restartEvents: [SeatRestartEvent] = []
    private(set) var restartBatches: [String: RestartBatchEvent] = [:]
    /// Restart idle seats is open, for one machine or all.
    var restartScope: RestartScope?
    /// The models to offer: the server's list once read, built in until then (core's ModelCatalogs).
    private(set) var modelCatalog: ModelCatalog = ModelCatalog.companion.BUILT_IN
    /// Seat profiles with their derived status, live from "profile" events.
    private(set) var profiles: [Profile] = []
    /// The profile open in the Profiles section; nil with [creatingProfile] for a new one.
    var selectedProfile: Int64?
    var creatingProfile = false
    private(set) var socketConnected = false

    /// What the window shows: seats, the forum, fleet tasks or the account pool.
    enum Section: String, CaseIterable, Identifiable {
        case seats = "Seats", profiles = "Profiles", forum = "Forum", tasks = "Tasks", pool = "Pool"
        var id: String { rawValue }
    }

    var section: Section = Section(rawValue: UserDefaults.standard.string(forKey: "section") ?? "") ?? .seats {
        didSet { UserDefaults.standard.set(section.rawValue, forKey: "section") }
    }

    /// The seat on screen; the same one opens at the next launch. Picking one closes a fleet board.
    var selectedSeat: Int64? = (UserDefaults.standard.object(forKey: "selectedSeat") as? NSNumber)?.int64Value {
        didSet {
            #if CLAUDIVERSE_HARNESS
            if let seat = selectedSeat, seat != oldValue { Harness.switchStarted = (seat, Date()) }
            #endif
            UserDefaults.standard.set(selectedSeat.map { NSNumber(value: $0) }, forKey: "selectedSeat")
            if selectedSeat != nil { boardFleet = nil }
        }
    }

    #if CLAUDIVERSE_HARNESS
    /// The seat the harness's "adopt" opened the Adopt sheet for.
    var harnessAdopt: Seat?
    /// A forum channel the harness opens.
    var harnessChannel: String?
    #endif

    /// Background-task bars start unfolded (the harness sets it, for screenshots); folded otherwise.
    var backgroundBarsOpen = false

    /// The fleet whose board is on screen instead of a seat, opened from its header in the seat list.
    var boardFleet: String?

    /// Show a profile in the Profiles section (from a fleet board's member tile).
    func openProfile(_ id: Int64) {
        section = .profiles
        creatingProfile = false
        selectedProfile = id
    }

    /// A tab a fleet's board was asked to show (from a folded fleet in the seat list); the board on screen follows it.
    struct BoardTabRequest: Equatable {
        let fleet: String
        let tab: BoardTabs.Tab.ID
        /// Each request is new, so asking for the tab already asked for still switches back to it.
        let id = UUID()
    }

    private(set) var boardTabRequest: BoardTabRequest?

    /// The tab the main window's board shows, by fleet: what a folded fleet's row marks as picked.
    var shownBoardTab: [String: BoardTabs.Tab.ID] = [:]

    /// Each fleet's tabs as its board last had them, in any window: a folded fleet's row draws these, since SwiftUI
    /// does not redraw on a change to the defaults they are saved in.
    var boardTabs: [String: BoardTabs] = [:]

    /// A fleet's tabs: as a board has them, else as saved.
    func tabs(of fleet: String) -> BoardTabs { boardTabs[fleet] ?? BoardTabs.peek(fleet) }

    /// Show a fleet's board in the main window; with [tab], on that tab.
    func openBoard(_ fleet: String, tab: BoardTabs.Tab.ID? = nil) {
        if let tab {
            // A board opened now reads it as its last tab; one already on screen follows the request.
            BoardTabs.setLastTab(tab, fleet)
            boardTabRequest = BoardTabRequest(fleet: fleet, tab: tab)
        }
        selectedSeat = nil
        boardFleet = fleet
    }
    /// Opens the main window; set by the first view that can (the window may be closed).
    var openMainWindow: (() -> Void)?
    /// Opens a seat in a window of its own; set by the first view that can.
    var openSeatWindow: ((Int64) -> Void)?

    let access = AccessBox()
    let client: ClaudiverseClient
    let notifier = Notifier()
    /// The Pool section's data, shared by its sidebar and its page.
    let pool = PoolModel()
    /// Unsent messages per seat, kept until sent or cleared.
    let drafts = Drafts()
    /// The seats the main window has shown, newest first, kept loaded and live.
    @ObservationIgnored private var recentSeats: [SeatModel] = []
    /// Stopped seats kept among the newest; running ones stay up to [keptSeatLimit].
    private static let recentStoppedLimit = 4
    private static let keptSeatLimit = 12

    /**
     * The main window's model for [seatId]: the one already loaded if the seat
     * was shown before, else a new one. A seat shown stays loaded and follows
     * its seat live while it runs, so switching back shows it at once instead
     * of waiting for its history. A stopped one is kept only among the
     * [recentStoppedLimit] newest; at most [keptSeatLimit] are kept in all.
     */
    func recentSeat(_ seatId: Int64) -> SeatModel {
        let m: SeatModel
        if let i = recentSeats.firstIndex(where: { $0.session.seatId == seatId }) {
            m = recentSeats.remove(at: i)
        } else {
            m = SeatModel(client: client, seatId: seatId)
        }
        recentSeats.insert(m, at: 0)
        var kept: [SeatModel] = []
        for (i, r) in recentSeats.enumerated() {
            let running = r.state.seat?.status == "running"
            if kept.count < Self.keptSeatLimit && (i < Self.recentStoppedLimit || running) { kept.append(r) } else { r.close() }
        }
        recentSeats = kept
        return m
    }
    private var running: [Task<Void, Never>] = []

    init() {
        let box = access
        client = ClaudiverseClient(access: { box.value }, outboxStore: DefaultsOutboxStore())
        CoreLog.shared.sink = { line in log.info("\(line, privacy: .public)") }
        notifier.onOpen = { [weak self] seatId in self?.open(seatId: seatId) }
        #if CLAUDIVERSE_HARNESS
        Harness.start(self)
        #endif
        Task { await restore() }
    }

    // MARK: signing in

    /// A token from an earlier run: use it unless the server says it is no longer valid.
    private func restore() async {
        // Off the main thread: it is disk I/O, and launch must not wait on it.
        let stored = await Task.detached { TokenStore.token() }.value
        guard !serverURL.isEmpty, let token = stored else {
            phase = .signedOut
            return
        }
        access.set(baseUrl: serverURL, token: token)
        do {
            let me = try await client.api.me()
            email = me.email
            scope = me.scope
            begin()
        } catch {
            if error.httpStatus == 401 {
                await Task.detached { TokenStore.delete() }.value
                access.set(baseUrl: serverURL, token: "")
                phase = .signedOut
            } else {
                // Unreachable (asleep, off the network): the token may well be fine.
                log.info("token not checked: \(error.message, privacy: .public)")
                begin()
            }
        }
    }

    /// Sign in and mint a super token named after this Mac. Throws with the server's reason.
    func signIn(server: String, email: String, password: String) async throws {
        let base = Self.normalise(server)
        serverURL = base
        access.set(baseUrl: base, token: "")
        let result = try await client.api.signIn(email: email, password: password, client: Self.clientName())
        await Task.detached { TokenStore.save(result.token) }.value
        access.set(baseUrl: base, token: result.token)
        self.email = result.email
        scope = result.scope
        begin()
    }

    /// Revoke this Mac's token on the server, then forget it here.
    func signOut() async {
        do { try await client.api.signOut() } catch { log.info("sign-out not confirmed: \(error.message, privacy: .public)") }
        running.forEach { $0.cancel() }
        running = []
        client.humanRequests.stop()
        client.fleets.stop()
        client.profiles.stop()
        client.poolEvents.stop()
        client.restarts.stop()
        recentSeats.forEach { $0.close() }
        recentSeats = []
        await Task.detached { TokenStore.delete() }.value
        access.set(baseUrl: serverURL, token: "")
        seats = []
        needsYou = [:]
        selectedSeat = nil
        email = nil
        phase = .signedOut
    }

    /// "http://host:4010" from what was typed: a scheme added when missing, no trailing slash.
    static func normalise(_ typed: String) -> String {
        var s = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        if !s.isEmpty && !s.contains("://") { s = "http://" + s }
        return s
    }

    /// This Mac's name as the token list shows it: 1-64 of letters, digits, space . _ -
    static func clientName() -> String {
        // "Sam's MacBook Pro" reads better as "Sams MacBook Pro" than with a dash.
        let name = (Host.current().localizedName ?? "Mac").replacingOccurrences(of: "'", with: "").replacingOccurrences(of: "\u{2019}", with: "")
        let allowed = name.unicodeScalars.map { c -> Character in
            CharacterSet.alphanumerics.contains(c) && c.isASCII || " ._-".unicodeScalars.contains(c) ? Character(c) : "-"
        }
        let cleaned = String(allowed).trimmingCharacters(in: .whitespaces)
        return String((cleaned.isEmpty ? "Mac" : cleaned).prefix(64))
    }

    // MARK: running

    private func begin() {
        phase = .signedIn
        client.socket.reconnectNow()
        client.humanRequests.start()
        client.fleets.start()
        client.profiles.start()
        client.poolEvents.start()
        client.restarts.start()
        client.models.refresh()
        drafts.removeStalePastes()
        notifier.requestPermission()
        running = [
            Task { [weak self] in
                while !Task.isCancelled {
                    await self?.refreshSeats()
                    try? await Task.sleep(for: .seconds(5))
                }
            },
            Task { [weak self, client] in
                // The list, not the map: a Kotlin map keyed by Long crashed here on a failed key cast.
                for await list in client.humanRequests.requests {
                    guard let self else { return }
                    let byId = Dictionary(list.map { ($0.sessionId, $0) }, uniquingKeysWith: { first, _ in first })
                    self.notifier.update(byId)
                    self.needsYou = byId
                }
            },
            Task { [weak self, client] in
                for await notice in client.spawns.notice { self?.spawnNotice = notice }
            },
            Task { [weak self, client] in
                for await connected in client.socket.connected { self?.socketConnected = connected.boolValue }
            },
            Task { [weak self, client] in
                for await items in client.outbox.items { self?.queued = items }
            },
            Task { [weak self, client] in
                for await all in client.fleets.all { self?.fleets = all }
            },
            Task { [weak self, client] in
                for await events in client.restarts.events { self?.restartEvents = events }
            },
            Task { [weak self, client] in
                for await catalog in client.models.catalog { self?.modelCatalog = catalog }
            },
            Task { [weak self, client] in
                for await batches in client.restarts.batches { self?.restartBatches = batches }
            },
            Task { [weak self, client] in
                for await all in client.profiles.all { self?.profiles = all }
            },
            Task { [weak self, client] in
                // The event says what changed, not the list: read it again.
                for await change in client.poolEvents.latest {
                    guard let self, let change else { continue }
                    self.pool.lastChange = change
                    await self.pool.load(client.api)
                }
            },
        ]
    }

    func refreshSeats() async {
        guard phase == .signedIn else { return }
        do {
            seats = try await client.api.seats(includeStopped: includeStopped)
            seatsError = nil
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            seatsError = error.message
        }
    }

    /// Show a seat: bring the window forward and select it.
    func open(seatId: Int64) {
        section = .seats
        selectedSeat = seatId
        openMainWindow?()
        NSApp.activate()
    }

    func dismissSpawnNotice() { client.spawns.dismiss() }
}
