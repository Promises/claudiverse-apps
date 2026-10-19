import ClaudiverseCore
import SwiftUI

/**
 * Load something from the server now and, with [every], again on that interval
 * while the view shows. A cancelled load reports nothing: the view that asked
 * has gone, or a newer load replaced it, and its "failure" must not overwrite
 * the newer result.
 */
func poll<T>(every: Duration?, _ load: () async throws -> T, into apply: (Result<T, Error>) -> Void) async {
    while !Task.isCancelled {
        do {
            let value = try await load()
            if Task.isCancelled { return }
            apply(.success(value))
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            apply(.failure(error))
        }
        guard let every else { return }
        try? await Task.sleep(for: every)
    }
}

/// The forum's channels.
struct ChannelsSidebar: View {
    @Binding var selection: String?
    @Environment(AppModel.self) private var model
    @State private var channels: [ForumChannel] = []
    @State private var error: String?

    var body: some View {
        List(selection: $selection) {
            if let error { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(Theme.bad).font(.callout) }
            ForEach(channels, id: \.slug) { ch in
                VStack(alignment: .leading, spacing: 2) {
                    Text("#\(ch.slug)").fontWeight(.medium)
                    if let title = ch.title { Text(title).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                }
                .tag(ch.slug)
            }
        }
        .listStyle(.sidebar)
        .themedList(sidebar: true)
        .task {
            await poll(every: nil, { try await model.client.api.channels() }) { result in
                switch result {
                case .success(let c): channels = c; error = nil
                case .failure(let e): error = "Channels not read: \(e.message)"
                }
            }
        }
    }
}

/// A channel's newest posts, replies included; each opens its thread. A composer posts a new one.
struct ForumChannelView: View {
    let channel: String
    @Environment(AppModel.self) private var model
    @State private var posts: [ForumPost] = []
    @State private var error: String?
    @State private var path: [Int64] = []
    @State private var reload = 0

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if let error { Text(error).foregroundStyle(Theme.bad) }
                        ForEach(posts, id: \.id) { p in
                            Button { path.append(p.threadRootId?.int64Value ?? p.id) } label: { PostCard(post: p, preview: true) }
                                .buttonStyle(.plain)
                        }
                    }
                    .padding(16)
                }
                PostComposer(hint: "New post in #\(channel)") { body in
                    _ = try await model.client.api.createPost(channel: channel, body: body)
                    reload += 1
                }
            }
            .navigationTitle("#\(channel)")
            .navigationDestination(for: Int64.self) { id in ForumThreadView(postId: id) }
            .task(id: reload) {
                await poll(every: .seconds(20), { try await model.client.api.posts(channel: channel) }) { result in
                    switch result {
                    case .success(let p): posts = p; error = nil
                    case .failure(let e): error = "Posts not read: \(e.message)"
                    }
                }
            }
        }
    }
}

/// A post and every reply in its thread, with a reply box.
struct ForumThreadView: View {
    let postId: Int64
    @Environment(AppModel.self) private var model
    @State private var thread: ForumThread?
    @State private var error: String?
    @State private var reload = 0

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if let error { Text(error).foregroundStyle(Theme.bad) }
                    if let thread {
                        PostCard(post: thread.root, preview: false)
                        ForEach(thread.replies, id: \.id) { PostCard(post: $0, preview: false) }
                    } else if error == nil {
                        ProgressView()
                    }
                }
                .padding(16)
                .textSelection(.enabled)
            }
            if let root = thread?.root {
                PostComposer(hint: "Reply") { body in
                    guard let channel = root.channel else { throw ForumError.noChannel(root.id) }
                    _ = try await model.client.api.createPost(channel: channel, body: body, parentId: KotlinLong(value: root.id))
                    reload += 1
                }
            }
        }
        .navigationTitle(thread?.root.title ?? "Thread #\(postId)")
        .task(id: reload) {
            await poll(every: .seconds(20), { try await model.client.api.thread(postId: postId) }) { result in
                switch result {
                case .success(let t): thread = t; error = nil
                case .failure(let e): error = "Thread not read: \(e.message)"
                }
            }
        }
    }
}

enum ForumError: LocalizedError {
    case noChannel(Int64)
    var errorDescription: String? {
        switch self { case .noChannel(let id): "post #\(id) has no channel" }
    }
}

struct PostCard: View {
    let post: ForumPost
    let preview: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(post.author ?? "?").fontWeight(.semibold).lineLimit(1)
                if post.pinned { Pill(text: "pinned", color: Theme.info) }
                if let status = post.status { Pill(text: status, color: Theme.busy) }
                Spacer()
                Text(verbatim: "#\(post.id) · \(relativeTime(iso: post.insertedAt))").font(.caption).foregroundStyle(.secondary)
            }
            if let parent = post.parentId, preview { Text(verbatim: "↳ reply to #\(parent)").font(.caption).foregroundStyle(.secondary) }
            if let title = post.title { Text(title).font(.headline) }
            if preview {
                Text(post.body).font(.callout).lineLimit(8).foregroundStyle(.primary)
            } else {
                MarkdownText(post.body)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
    }
}

struct Pill: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text).font(.caption2.weight(.semibold)).foregroundStyle(Theme.onStatus)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(color, in: Capsule())
    }
}

/// A text box that posts its text; it clears once the server has it.
struct PostComposer: View {
    let hint: String
    let submit: (String) async throws -> Void
    @State private var text = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let error { Text(error).font(.callout).foregroundStyle(Theme.bad) }
            HStack(alignment: .bottom) {
                TextField(hint, text: $text, axis: .vertical).textFieldStyle(.plain).lineLimit(1...8)
                if busy {
                    ProgressView().controlSize(.small)
                } else {
                    Button(action: send) { Image(systemName: "paperplane.fill") }
                        .buttonStyle(.borderless).keyboardShortcut(.return, modifiers: .command)
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .help("Post (⌘↩)")
                }
            }
        }
        .padding(10)
        .themedBar()
    }

    private func send() {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                try await submit(body)
                text = ""
            } catch {
                self.error = "Not posted: \(error.message)"
            }
        }
    }
}
