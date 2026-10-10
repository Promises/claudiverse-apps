import AppKit
import ClaudiverseCore
import SwiftUI

/**
 * Images opened in the app, as a chat app's viewer shows them: over the
 * window on a dark backdrop, fitted, with the transcript's other images a
 * step away (← →), zoom (double-click, pinch), and Copy, Save to Downloads
 * and Open in Preview. Esc or a click on the backdrop closes it.
 *
 * One per window: `.imageViewer()` on a window's root. A transcript opens it
 * through the `showImage` environment action.
 */
@Observable
final class ImageViewerModel {
    struct Item {
        /// The image's cache key (its file, or its place in the transcript).
        let key: String
        let image: TranscriptImage
        /// "You · 21:53"
        let caption: String
    }

    private(set) var items: [Item] = []
    var index: Int?

    func open(_ items: [Item], at key: String) {
        guard let i = items.firstIndex(where: { $0.key == key }) else { return }
        self.items = items
        index = i
    }

    func step(_ by: Int) {
        guard let i = index else { return }
        index = min(max(i + by, 0), items.count - 1)
    }

    func close() { index = nil }
}

extension EnvironmentValues {
    /// Opens the window's image viewer at the image with this cache key; nil where no transcript offers one.
    @Entry var showImage: ((String) -> Void)? = nil
}

extension View {
    /// Gives this window an image viewer, drawn over everything in it.
    func imageViewer() -> some View { modifier(ImageViewerHost()) }
}

private struct ImageViewerHost: ViewModifier {
    @State private var viewer = ImageViewerModel()

    func body(content: Content) -> some View {
        content
            .environment(viewer)
            .overlay { if viewer.index != nil { ImageViewer(model: viewer) } }
    }
}

private struct ImageViewer: View {
    let model: ImageViewerModel
    @Environment(AppModel.self) private var app

    var body: some View {
        if let i = model.index, model.items.indices.contains(i) {
            let item = model.items[i]
            ZStack {
                Color.black.opacity(0.9).ignoresSafeArea().onTapGesture { model.close() }
                ZoomableImage(item: item, access: app.access.value, onBackdrop: { model.close() })
                    .id(item.key)
                    .padding(.vertical, 56).padding(.horizontal, 64)
                VStack {
                    header(item, i)
                    Spacer()
                }
                HStack {
                    arrow("chevron.left", enabled: i > 0, key: .leftArrow) { model.step(-1) }
                    Spacer()
                    arrow("chevron.right", enabled: i < model.items.count - 1, key: .rightArrow) { model.step(1) }
                }
                .padding(.horizontal, 14)
            }
            .environment(\.colorScheme, .dark)
            .transition(.opacity)
        }
    }

    private func header(_ item: ImageViewerModel.Item, _ i: Int) -> some View {
        HStack(spacing: 14) {
            Text(model.items.count > 1 ? "\(item.caption) · \(i + 1) of \(model.items.count)" : item.caption)
                .font(.callout.weight(.medium)).foregroundStyle(.white)
            Spacer()
            Button { TranscriptImages.copy(item.key) } label: { Image(systemName: "doc.on.doc") }.help("Copy Image")
            Button { TranscriptImages.saveToDownloads(item.key, item.image.mediaType) } label: { Image(systemName: "arrow.down.to.line") }.help("Save to Downloads")
            Button { TranscriptImages.open(item.key, item.image.mediaType) } label: { Image(systemName: "arrow.up.forward.app") }.help("Open in Preview")
            Button { model.close() } label: { Image(systemName: "xmark") }
                .keyboardShortcut(.cancelAction)
                .help("Close (Esc)")
        }
        .buttonStyle(.borderless)
        .font(.title3)
        .foregroundStyle(.white)
        .padding(.horizontal, 18).padding(.vertical, 14)
    }

    private func arrow(_ symbol: String, enabled: Bool, key: KeyEquivalent, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.title2.weight(.semibold)).frame(width: 40, height: 40)
                .background(.white.opacity(0.12), in: Circle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .keyboardShortcut(key, modifiers: [])
        .opacity(enabled ? 1 : 0)
        .disabled(!enabled)
    }
}

/// The image fitted to the space; double-click or pinch to zoom in, then scroll to pan.
private struct ZoomableImage: View {
    let item: ImageViewerModel.Item
    let access: ServerAccess
    let onBackdrop: () -> Void
    @State private var picture: NSImage?
    @State private var failed: String?
    @State private var zoom: CGFloat = 1
    @State private var settledZoom: CGFloat = 1

    var body: some View {
        GeometryReader { geo in
            if let picture {
                let fit = Self.fitted(picture.size, in: geo.size)
                ScrollView([.horizontal, .vertical]) {
                    Image(nsImage: picture).resizable().interpolation(.high)
                        .frame(width: fit.width * zoom, height: fit.height * zoom)
                        .onTapGesture(count: 2) {
                            withAnimation(.easeOut(duration: 0.15)) { zoom = zoom > 1 ? 1 : 2.5 }
                            settledZoom = zoom
                        }
                        // A single click on the image is not a click on the backdrop.
                        .onTapGesture(count: 1) {}
                        .frame(minWidth: geo.size.width, minHeight: geo.size.height)
                        .contentShape(Rectangle())
                        .onTapGesture { onBackdrop() }
                }
                .scrollIndicators(zoom > 1 ? .automatic : .hidden)
                .scrollDisabled(zoom <= 1)
                .gesture(
                    MagnifyGesture()
                        .onChanged { zoom = min(max(settledZoom * $0.magnification, 1), 8) }
                        .onEnded { _ in settledZoom = zoom }
                )
            } else if let failed {
                Text(failed).foregroundStyle(.secondary).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task {
            do {
                picture = try await TranscriptImages.picture(item.key, item.image, access: access)
            } catch {
                if !(error is CancellationError) { failed = "Image not loaded: \(error.localizedDescription)" }
            }
        }
    }

    /// The largest size that fits, up to twice the image's own (a small image is not blown up past that).
    static func fitted(_ size: CGSize, in space: CGSize) -> CGSize {
        guard size.width > 0, size.height > 0 else { return space }
        let scale = min(space.width / size.width, space.height / size.height, 2)
        return CGSize(width: size.width * scale, height: size.height * scale)
    }
}
