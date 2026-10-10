import ClaudiverseCore
import SwiftUI
import UniformTypeIdentifiers

/**
 * The transcript, oldest at the top. It follows new messages while the view is
 * at the bottom; scrolled up to read, it stays put until scrolled back down.
 *
 * The rows redraw only when [version] changes: a busy seat streams many
 * updates a second, and redrawing (and re-parsing) every row for each one
 * kept the app at half a core.
 *
 * Only the newest [pageSize] rows (about a screenful) are drawn at first,
 * more on scrolling up to the top or asking: laying out a whole history
 * (hundreds of rows) is what made switching seats slow.
 */
struct TranscriptView: View {
    let rows: [TimelineRow]
    let version: Int64
    let streaming: String
    let session: SeatSession
    @Environment(ImageViewerModel.self) private var viewer: ImageViewerModel?
    /// The rows as of now, for the viewer: the rows view skips redraws, so a captured copy would go stale.
    @State private var current = CurrentRows()
    @State private var position = ScrollPosition(edge: .bottom)
    /// Locked to the bottom: new messages and streaming text keep it there.
    @State private var following = true
    /// Whether the view is at (or within a few points of) the bottom right now.
    @State private var atBottom = true
    /// A scroll by the operator (trackpad, wheel, scroller) is under way.
    @State private var manual = false
    /// The first row drawn, by key: fixed while new rows arrive, so they only append. A window that slid
    /// by one row per message removed the top row each time, and the lazy stack, re-estimating, showed
    /// blank space until it caught up. Nil: the newest [pageSize].
    @State private var firstKey: String?
    #if CLAUDIVERSE_HARNESS
    @State private var firstRowsAt: Date?
    @State private var lastLayoutAt: Date?
    @State private var settle: Task<Void, Never>?
    #endif

    static let pageSize = 25

    /// Where drawing starts: at [firstKey] while it is still in the rows (a replay can drop it), else a page from the end.
    private var start: Int {
        if let firstKey, let i = rows.firstIndex(where: { $0.key == firstKey }) { return i }
        return max(0, rows.count - Self.pageSize)
    }

    private var visible: [TimelineRow] { start == 0 ? rows : Array(rows[start...]) }

    /// Changes when the drawn range starts elsewhere: the rows view redraws for it as for a new version.
    private var shown: Int { rows.count - start }

    var body: some View {
        ScrollView {
            // Not lazy: only a window of rows is drawn (a page, growing to four before it moves up), so laying
            // them all out is cheap. A lazy stack estimated the heights of rows not yet drawn, and pinned to the
            // bottom of that estimate, a tile showed blank space for seconds at a time.
            VStack(alignment: .leading, spacing: 10) {
                if start > 0 {
                    Button("Show earlier messages (\(start))") { showEarlier() }
                        .buttonStyle(.link)
                        .frame(maxWidth: .infinity)
                        // Scrolled up to it by hand: load on without a click. Not while opening, when it can show
                        // above a short first page, or that would undo the point of drawing a page at a time.
                        .onAppear { if manual { showEarlier() } }
                }
                TranscriptRows(rows: visible, version: version, shown: shown, session: session).equatable()
                if !streaming.isEmpty {
                    ChatBubble(fromClaude: true) { MarkdownText(streaming, fill: false).foregroundStyle(.secondary) }
                }
            }
            .padding(16)
            .textSelection(.enabled)
            .environment(\.showImage, viewer.map { [current] viewer in { key in viewer.open(Self.images(current.rows), at: key) } })
        }
        .scrollPosition($position)
        .defaultScrollAnchor(.bottom)
        // A clean edge under the title bar: the soft default left the title on top of the text.
        .scrollEdgeEffectStyle(.hard, for: .top)
        .onScrollGeometryChange(for: Bool.self) { geo in
            geo.visibleRect.maxY >= geo.contentSize.height - 24
        } action: { _, bottom in
            atBottom = bottom
        }
        // Only the operator's own scrolling changes whether it follows: content growing below the view
        // is not a scroll away. Back at the bottom, it follows again.
        .onScrollPhaseChange { _, phase in
            switch phase {
            case .interacting, .decelerating: manual = true
            case .idle:
                if manual { following = atBottom }
                manual = false
            default: break
            }
        }
        // The rows are laid out lazily, their heights estimated until drawn: a jump to the bottom lands on
        // the estimated bottom, and the content keeps growing as real heights come in. Following, it stays
        // pinned through that too, which is also what makes a seat open at its bottom.
        .onScrollGeometryChange(for: CGFloat.self) { geo in geo.contentSize.height } action: { _, _ in
            stickToBottom()
            #if CLAUDIVERSE_HARNESS
            switchTiming()
            #endif
        }
        .onChange(of: version, initial: true) {
            current.rows = rows
            // Fix where drawing starts once there are rows. Following a seat for hours, it is moved up to a
            // page from the end now and then (not per message), so the drawn rows don't grow without bound.
            if firstKey == nil || (following && !manual && rows.count - start > Self.pageSize * 4), !rows.isEmpty {
                firstKey = rows[max(0, rows.count - Self.pageSize)].key
            }
            stickToBottom()
            #if CLAUDIVERSE_HARNESS
            switchTiming()
            #endif
        }
        .onChange(of: streaming) { stickToBottom() }
        #if CLAUDIVERSE_HARNESS
        .onReceive(NotificationCenter.default.publisher(for: Harness.reveal)) { note in
            guard let needle = note.object as? String, let index = rows.firstIndex(where: { Self.text(of: $0).contains(needle) }) else { return }
            let key = rows[index].key
            following = false
            if index < start { firstKey = key }
            position.scrollTo(id: key, anchor: .top)
        }
        #endif
    }

    #if CLAUDIVERSE_HARNESS
    /// A row's message text, for the harness's "reveal".
    private static func text(of row: TimelineRow) -> String {
        guard case .entry(let e) = onEnum(of: row) else { return "" }
        switch onEnum(of: e.item) {
        case .userText(let u): return u.text
        case .assistantText(let a): return a.text
        default: return ""
        }
    }
    #endif

    /// Every image in the transcript, oldest first, for the viewer to step through.
    static func images(_ rows: [TimelineRow]) -> [ImageViewerModel.Item] {
        // Pasted images and tool results' pictures, in transcript order: the viewer steps through them all.
        rows.flatMap { row -> [ImageViewerModel.Item] in
            guard case .entry(let e) = onEnum(of: row) else { return [] }
            let when = e.at.map { Timeline.shared.label(at: $0.int64Value) }
            let (key, images, who): (String, [TranscriptImage], String) = switch onEnum(of: e.item) {
            case .userText(let u): (u.key, u.images, "You")
            case .toolUse(let t): (t.key, t.images, "\(t.name) · \(t.summary)")
            default: ("", [], "")
            }
            return images.enumerated().map { i, image in
                ImageViewerModel.Item(key: image.url ?? "\(key)#\(i)", image: image, caption: [who, when].compactMap { $0 }.joined(separator: " · "))
            }
        }
    }

    /// One page more, keeping the row that was first in view where it was.
    private func showEarlier() {
        let anchor = visible.first?.key
        following = false
        firstKey = rows[max(0, start - Self.pageSize)].key
        if let anchor { DispatchQueue.main.async { position.scrollTo(id: anchor, anchor: .top) } }
    }

    #if CLAUDIVERSE_HARNESS
    /**
     * After a switch in the main window: when its rows first showed, and when
     * the view settled (no layout change for 0.4 s), from the selection change.
     */
    private func switchTiming() {
        guard let started = Harness.switchStarted, started.seat == session.seatId else { return }
        let now = Date()
        if !rows.isEmpty, firstRowsAt == nil {
            firstRowsAt = now
            Harness.note("switch \(started.seat): first rows (\(rows.count)) after \(Int(now.timeIntervalSince(started.at) * 1000)) ms")
        }
        lastLayoutAt = now
        settle?.cancel()
        settle = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled, let last = lastLayoutAt, Harness.switchStarted?.at == started.at else { return }
            Harness.note("switch \(started.seat): settled after \(Int(last.timeIntervalSince(started.at) * 1000)) ms")
            Harness.switchStarted = nil
        }
    }
    #endif

    private func stickToBottom() {
        guard following, !manual else { return }
        position.scrollTo(edge: .bottom)
    }
}

final class CurrentRows {
    var rows: [TimelineRow] = []
}

/// The transcript's rows; equal, and so not redrawn, while the transcript's version is unchanged.
struct TranscriptRows: View, Equatable {
    let rows: [TimelineRow]
    let version: Int64
    /// How many rows the transcript draws: a change redraws, like a new version.
    let shown: Int
    let session: SeatSession

    static func == (a: TranscriptRows, b: TranscriptRows) -> Bool { a.version == b.version && a.shown == b.shown && a.session === b.session }

    var body: some View {
        ForEach(rows, id: \.key) { row in
            rowView(row)
        }
    }

    @ViewBuilder
    private func rowView(_ row: TimelineRow) -> some View {
        switch onEnum(of: row) {
        case .divider(let d):
            Text(Timeline.shared.label(at: d.at)).font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity).padding(.top, 6)
        case .resumed:
            HStack {
                VStack { Divider() }
                Text("RESUMED").font(.caption2.weight(.semibold)).foregroundStyle(Theme.accent)
                VStack { Divider() }
            }
            .padding(.vertical, 4)
        case .entry(let e):
            // No click handler on the row: on macOS one takes the mouse from the text inside, and its text could not
            // be selected. The time shows while the pointer is over the row; Copy (right-click) takes the whole message.
            HoverTime(time: e.at.map { Timeline.shared.label(at: $0.int64Value) }) {
                ItemView(item: e.item, session: session)
            }
                .contextMenu {
                    if let text = clipboardText(item: e.item) {
                        Button("Copy") { Self.copy(text) }
                    }
                    if let at = e.at?.int64Value {
                        Text(Timeline.shared.label(at: at))
                    }
                }
        }
    }

    static func copy(_ text: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

}

/**
 * A row with its time showing while the pointer is over it: on every row,
 * with nothing to click to show or hide it. Drawn over the gap above the
 * row, so nothing moves when it appears.
 */
struct HoverTime<Content: View>: View {
    let time: String?
    @ViewBuilder let content: Content
    @State private var hovering = false

    var body: some View {
        content
            .onHover { hovering = $0 }
            .overlay(alignment: .topLeading) {
                if hovering, let time {
                    Text(time)
                        .font(.caption2).foregroundStyle(.secondary)
                        .padding(.horizontal, 6).padding(.vertical, 1)
                        .background(.background.opacity(0.9), in: Capsule())
                        .offset(y: -12)
                        .allowsHitTesting(false)
                        .transition(.opacity)
                }
            }
    }
}

/// One transcript item.
struct ItemView: View {
    let item: TranscriptItem
    let session: SeatSession

    var body: some View {
        switch onEnum(of: item) {
        case .userText(let u):
            ChatBubble(fromClaude: false, label: u.injected ? "injected" : nil) {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(u.images.enumerated()), id: \.offset) { i, image in
                        TranscriptImageView(image: image, id: "\(u.key)#\(i)")
                    }
                    if !u.text.isEmpty { MarkdownText(u.text, fill: false) }
                }
            }
        case .assistantText(let a):
            ChatBubble(fromClaude: true) { MarkdownText(a.text, fill: false) }
        case .thinking(let t):
            ThinkingBubble(text: t.text)
        case .toolUse(let t):
            VStack(alignment: .leading, spacing: 4) {
                Disclosure(label: t.name, systemImage: t.resultIsError ? "exclamationmark.triangle" : "wrench.and.screwdriver",
                           summary: t.summary, tint: t.resultIsError ? Theme.bad : nil) {
                    CodeBlock(text: t.input)
                    if let result = t.result, !result.isEmpty { CodeBlock(text: result) }
                }
                // Pictures the tool returned (a Read of a PNG, a screenshot): shown, not folded away.
                if !t.images.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(alignment: .top, spacing: 8) {
                            ForEach(Array(t.images.enumerated()), id: \.offset) { i, image in
                                TranscriptImageView(image: image, id: "\(t.key)#\(i)")
                            }
                        }
                    }
                    .padding(.leading, 8)
                }
                AgentThreadLink(toolUseId: t.toolUseId)
            }
        case .question(let q):
            QuestionCard(question: q, session: session)
        case .permission(let p):
            PermissionCard(permission: p, session: session)
        case .event(let e):
            Disclosure(label: e.label, systemImage: "bolt", summary: e.summary) {
                Text(e.text).font(.callout.monospaced()).foregroundStyle(.secondary)
            }
        case .turnEnd(let t):
            Text(t.text).font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .center)
        case .note(let n):
            Text(n.text).font(.caption.italic()).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .center)
        }
    }
}

/**
 * A message bubble, as the phone draws them: Claude's on the left in a neutral
 * colour, the operator's on the right in violet, each with its corner nearest
 * the speaker squared off. It hugs short messages and leaves a margin on the
 * far side of long ones.
 */
struct ChatBubble<Content: View>: View {
    let fromClaude: Bool
    var label: String? = nil
    /// Claude's thinking: a lighter bubble with a dashed edge, apart from its replies.
    var thinking = false
    @ViewBuilder let content: () -> Content

    var body: some View {
        let shape = UnevenRoundedRectangle(topLeadingRadius: fromClaude ? 4 : 16, bottomLeadingRadius: 16,
                                           bottomTrailingRadius: 16, topTrailingRadius: fromClaude ? 16 : 4)
        HStack(spacing: 0) {
            if !fromClaude { Spacer(minLength: 60) }
            VStack(alignment: .leading, spacing: 2) {
                if let label { Text(label).font(.caption2).foregroundStyle(.secondary) }
                content()
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(thinking ? Theme.thinkingBubble : fromClaude ? Theme.claudeBubble : Theme.userBubble, in: shape)
            .overlay { if thinking { shape.strokeBorder(.secondary.opacity(0.35), style: StrokeStyle(lineWidth: 1, dash: [4, 3])) } }
            if fromClaude { Spacer(minLength: 60) }
        }
    }
}

/**
 * Claude's thinking, shown rather than folded away: it is often the most
 * interesting part. Long thinking shows its first lines until opened.
 */
struct ThinkingBubble: View {
    /// Trimmed: thinking often ends in blank lines, which drew an empty band in the bubble.
    let text: String
    @State private var expanded = false

    init(text: String) { self.text = text.trimmingCharacters(in: .whitespacesAndNewlines) }

    private static let shownLines = 6
    private var long: Bool { text.count > 600 || text.split(separator: "\n", omittingEmptySubsequences: false).count > Self.shownLines }

    var body: some View {
        ChatBubble(fromClaude: true, thinking: true) {
            VStack(alignment: .leading, spacing: 4) {
                Label("Thinking", systemImage: "brain").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Text(MarkdownText.inline(text)).font(.callout).italic().foregroundStyle(.secondary)
                    .lineLimit(expanded ? nil : Self.shownLines)
                if long {
                    Button(expanded ? "Show less" : "Show more") { expanded.toggle() }
                        .buttonStyle(.link).font(.caption)
                }
            }
        }
    }
}

/**
 * An image pasted into a message: a thumbnail in the bubble; a click opens it
 * full size in Preview. A stored image is fetched from the server with the
 * app's token; inline base64 is decoded.
 */
struct TranscriptImageView: View {
    let image: TranscriptImage
    /// Stable per image (its item's key and position), for the cache of inline images.
    let id: String
    @Environment(AppModel.self) private var model
    @State private var picture: NSImage?
    @State private var failed: String?

    var body: some View {
        Group {
            if let picture {
                Image(nsImage: picture).resizable().aspectRatio(contentMode: .fit)
                    .frame(maxWidth: min(360, picture.size.width), maxHeight: min(260, picture.size.height))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .contentShape(Rectangle())
                    .onTapGesture { show() }
                    .contextMenu {
                        Button("Open") { show() }
                        Button("Open in Preview") { TranscriptImages.open(cacheKey, image.mediaType) }
                        Button("Copy Image") { TranscriptImages.copy(cacheKey) }
                        Button("Save to Downloads") { TranscriptImages.saveToDownloads(cacheKey, image.mediaType) }
                    }
                    .help("Click to view")
            } else if let failed {
                Label(failed, systemImage: "photo").foregroundStyle(.secondary)
            } else {
                ProgressView().controlSize(.small).frame(width: 120, height: 80)
            }
        }
        .task(id: cacheKey) {
            do {
                picture = try await TranscriptImages.picture(cacheKey, image, access: model.access.value)
                failed = nil
            } catch {
                if error is CancellationError { return }
                failed = "Image not loaded: \(error.localizedDescription)"
            }
        }
    }

    /// A stored image by its file (it never changes), an inline one by its place in the transcript.
    var cacheKey: String { image.url ?? id }

    @Environment(\.showImage) private var showImage

    private func show() {
        if let showImage { showImage(cacheKey) } else { TranscriptImages.open(cacheKey, image.mediaType) }
    }
}

/**
 * Transcript images, decoded once: every new message redraws the visible
 * rows, and a stored image would otherwise be fetched again each time.
 */
enum TranscriptImages {
    private static let pictures = NSCache<NSString, NSImage>()
    private static let bytes = NSCache<NSString, NSData>()

    struct Unreadable: LocalizedError {
        var errorDescription: String? { "not an image" }
    }

    static func picture(_ key: String, _ image: TranscriptImage, access: ServerAccess) async throws -> NSImage {
        if let hit = pictures.object(forKey: key as NSString) { return hit }
        let data = try await self.data(image, access: access)
        guard let picture = NSImage(data: data) else { throw Unreadable() }
        bytes.setObject(data as NSData, forKey: key as NSString)
        pictures.setObject(picture, forKey: key as NSString)
        return picture
    }

    private static func data(_ image: TranscriptImage, access: ServerAccess) async throws -> Data {
        if let path = image.url {
            guard let url = URL(string: access.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path) else { throw URLError(.badURL) }
            var request = URLRequest(url: url)
            request.setValue("Bearer \(access.token)", forHTTPHeaderField: "Authorization")
            let (data, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw URLError(.badServerResponse, userInfo: [NSLocalizedDescriptionKey: "HTTP \(http.statusCode)"])
            }
            return data
        }
        guard let base64 = image.base64, let data = Data(base64Encoded: base64) else { throw Unreadable() }
        return data
    }

    static func copy(_ key: String) {
        guard let picture = pictures.object(forKey: key as NSString) else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.writeObjects([picture])
    }

    /// Written to a temporary file for Preview. Only an image already shown can be opened.
    static func open(_ key: String, _ mediaType: String) {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("Claudiverse-images", isDirectory: true)
        if let url = write(key, mediaType, into: folder) { NSWorkspace.shared.open(url) }
    }

    /// A copy in Downloads, shown in Finder.
    static func saveToDownloads(_ key: String, _ mediaType: String) {
        guard let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first,
              let url = write(key, mediaType, into: downloads) else { return }
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    /// The image's bytes in [folder], named after its file or message: "api_files_X9z2.png".
    private static func write(_ key: String, _ mediaType: String, into folder: URL) -> URL? {
        guard let data = bytes.object(forKey: key as NSString) as Data? else { return nil }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let ext = UTType(mimeType: mediaType)?.preferredFilenameExtension ?? "png"
        let name = String(key.map { $0.isLetter || $0.isNumber || $0 == "-" ? $0 : "_" }).trimmingCharacters(in: CharacterSet(charactersIn: "_"))
        let url = folder.appendingPathComponent(name).appendingPathExtension(ext)
        return (try? data.write(to: url)) != nil ? url : nil
    }
}

/// A collapsed row that opens to show more.
struct Disclosure<Content: View>: View {
    let label: String
    let systemImage: String
    let summary: String?
    var tint: Color? = nil
    @ViewBuilder let content: () -> Content
    @State private var open = false

    var body: some View {
        DisclosureGroup(isExpanded: $open) {
            VStack(alignment: .leading, spacing: 6) { content() }.padding(.top, 4)
        } label: {
            HStack(spacing: 6) {
                Image(systemName: systemImage).foregroundStyle(tint ?? .secondary)
                Text(label).font(.callout.weight(.medium))
                if let summary { Text(summary).font(.callout.monospaced()).foregroundStyle(.secondary).lineLimit(1) }
            }
        }
    }
}

struct CodeBlock: View {
    let text: String

    var body: some View {
        ScrollView(.horizontal) {
            Text(text).font(.callout.monospaced()).fixedSize(horizontal: true, vertical: false).padding(8)
        }
        .frame(maxHeight: 360)
        .background(Theme.codeBackground, in: RoundedRectangle(cornerRadius: 6))
    }
}

/// An AskUserQuestion: pick an option per question (or several, where allowed), or answer in your own words.
struct QuestionCard: View {
    let question: TranscriptItemQuestion
    let session: SeatSession
    /// Each question's choices, in the order picked.
    @State private var picked: [Int: [String]] = [:]
    @State private var other = ""
    @State private var sent = false

    /// Only an unanswered question gets live controls: a transcript is mostly history.
    private var live: Bool { question.answer == nil && !sent }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Question").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            ForEach(Array(question.questions.enumerated()), id: \.offset) { index, q in
                VStack(alignment: .leading, spacing: 6) {
                    if let header = q.header { Text(header).font(.caption.weight(.semibold)).foregroundStyle(Theme.accent) }
                    Text(q.question).fontWeight(.medium)
                    ForEach(q.options, id: \.self) { option in
                        let label = (option.first as String?) ?? ""
                        let chosen = picked[index, default: []].contains(label)
                        Button { toggle(index, label, multi: q.multiSelect) } label: {
                            HStack(alignment: .firstTextBaseline) {
                                Image(systemName: chosen ? "checkmark.circle.fill" : "circle")
                                VStack(alignment: .leading) {
                                    Text(label)
                                    if let detail = option.second as String? { Text(detail).font(.caption).foregroundStyle(.secondary) }
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        .disabled(!live)
                    }
                }
            }
            if live {
                TextField("Or answer in your own words", text: $other, axis: .vertical).textFieldStyle(.roundedBorder)
                HStack {
                    Button("Answer") {
                        if session.answer(toolUseId: question.toolUseId, response: composed, answers: structured) { sent = true }
                    }
                    .buttonStyle(.borderedProminent).disabled(composed.isEmpty)
                    Button("Decline") {
                        if session.decline(toolUseId: question.toolUseId) { sent = true }
                    }
                }
            } else {
                Text(question.answer.map { "Answered: \($0)" } ?? "Sent").font(.caption).foregroundStyle(.secondary).lineLimit(3)
            }
        }
        .padding(12)
        .background(Theme.accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
    }

    private var composed: String {
        let own = other.trimmingCharacters(in: .whitespacesAndNewlines)
        if !own.isEmpty { return own }
        return composeAnswer(questions: question.questions, picked: question.questions.indices.map { picked[$0, default: []] })
    }

    /// Each question's own choice, when the answer is picked choices; none for an answer in the operator's own words.
    private var structured: [String: String] {
        guard other.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [:] }
        return answerMap(questions: question.questions, picked: question.questions.indices.map { picked[$0, default: []] })
    }

    private func toggle(_ index: Int, _ label: String, multi: Bool) {
        var current = picked[index, default: []]
        if !multi { current = [label] } else if let at = current.firstIndex(of: label) { current.remove(at: at) } else { current.append(label) }
        picked[index] = current
    }
}

/// A tool waiting for permission: allow or deny it here.
struct PermissionCard: View {
    let permission: TranscriptItemPermission
    let session: SeatSession

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "hand.raised.fill").foregroundStyle(permission.pending ? Theme.attention : .secondary)
                Text(permission.pending ? "\(permission.toolName) wants permission" : "\(permission.toolName) — answered").fontWeight(.medium)
            }
            if let detail = permission.detail { Text(detail).font(.callout) }
            if !permission.input.isEmpty { CodeBlock(text: permission.input) }
            if permission.pending {
                HStack {
                    Button("Allow") { session.permission(requestId: permission.requestId, allow: true) }.buttonStyle(.borderedProminent)
                    Button("Deny") { session.permission(requestId: permission.requestId, allow: false) }
                }
            }
        }
        .padding(12)
        .background((permission.pending ? Theme.attention : Color.secondary).opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
    }
}

extension EnvironmentValues {
    /// The seat's agents by the tool call that spawned them, for a tool row to open its agent's thread.
    @Entry var agentsByToolUse: [String: SeatAgent] = [:]
    /// Show an agent's thread (by agent id) in place of main's.
    @Entry var openAgentThread: ((String) -> Void)? = nil
}

/// Under an Agent tool call that spawned a subagent: a link to that subagent's own thread.
struct AgentThreadLink: View {
    let toolUseId: String
    @Environment(\.agentsByToolUse) private var agents
    @Environment(\.openAgentThread) private var open

    var body: some View {
        if let agent = agents[toolUseId], let open {
            Button { open(agent.id) } label: {
                Label("Open \(agent.type)'s thread", systemImage: "arrow.turn.down.right")
            }
            .buttonStyle(.link)
            .font(.callout)
            .padding(.leading, 8)
        }
    }
}
