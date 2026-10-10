import AppKit
import SwiftUI
import UniformTypeIdentifiers

/**
 * The message box's text: Return sends, Shift- or Option-Return starts a new
 * line, and pasting an image or files hands them to [onPasteFiles] instead
 * of inserting text. It grows with its text up to [maxLines], then scrolls.
 *
 * An NSTextView rather than a TextField: a TextField's field editor takes
 * ⌘V and Shift-Return itself, dropping pasted images and the new line.
 */
struct ComposerField: NSViewRepresentable {
    @Binding var text: String
    /// The height the text needs, for the caller to size the field with.
    @Binding var height: CGFloat
    var maxLines = 10
    let onSend: () -> Void
    let onPasteFiles: ([URL]) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeNSView(context: Context) -> NSScrollView {
        let textView = ComposerTextView()
        textView.isRichText = false
        textView.importsGraphics = false
        textView.allowsUndo = true
        textView.font = .systemFont(ofSize: NSFont.systemFontSize)
        textView.textColor = .labelColor
        textView.drawsBackground = false
        textView.textContainerInset = .zero
        textView.textContainer?.lineFragmentPadding = 0
        textView.isAutomaticQuoteSubstitutionEnabled = false
        textView.isAutomaticDashSubstitutionEnabled = false
        textView.isVerticallyResizable = true
        textView.isHorizontallyResizable = false
        textView.autoresizingMask = [.width]
        textView.textContainer?.widthTracksTextView = true
        textView.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        textView.delegate = context.coordinator
        textView.string = text

        let scroll = NSScrollView()
        scroll.drawsBackground = false
        scroll.borderType = .noBorder
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.documentView = textView
        context.coordinator.attach(textView)
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        context.coordinator.parent = self
        guard let textView = scroll.documentView as? ComposerTextView else { return }
        if textView.string != text {
            textView.string = text
            context.coordinator.measure(textView)
        }
    }

    final class Coordinator: NSObject, NSTextViewDelegate {
        var parent: ComposerField

        init(_ parent: ComposerField) { self.parent = parent }

        func attach(_ textView: ComposerTextView) {
            textView.onSend = { [weak self] in self?.parent.onSend() }
            textView.onPasteFiles = { [weak self] in self?.parent.onPasteFiles($0) }
            textView.onWidthChange = { [weak self, weak textView] in
                if let self, let textView { self.measure(textView) }
            }
        }

        func textDidChange(_ notification: Notification) {
            guard let textView = notification.object as? NSTextView else { return }
            parent.text = textView.string
            measure(textView)
        }

        /// The text's height, at least one line and at most [maxLines].
        func measure(_ textView: NSTextView) {
            guard let layout = textView.layoutManager, let container = textView.textContainer, let font = textView.font else { return }
            layout.ensureLayout(for: container)
            let line = layout.defaultLineHeight(for: font)
            let needed = min(max(layout.usedRect(for: container).height, line), line * CGFloat(parent.maxLines)).rounded(.up)
            guard abs(needed - parent.height) > 0.5 else { return }
            // Not during the view update that may have called this.
            DispatchQueue.main.async { self.parent.height = needed }
        }
    }
}

final class ComposerTextView: NSTextView {
    var onSend: (() -> Void)?
    var onPasteFiles: (([URL]) -> Void)?
    var onWidthChange: (() -> Void)?

    override func keyDown(with event: NSEvent) {
        let isReturn = event.keyCode == 36 || event.keyCode == 76 // Return, keypad Enter
        // While an input method is composing, Return confirms the composition.
        if isReturn, !hasMarkedText() {
            let modifiers = event.modifierFlags.intersection([.shift, .option, .command, .control])
            if modifiers == .shift || modifiers == .option {
                insertNewlineIgnoringFieldEditor(nil)
                return
            }
            if modifiers.isEmpty {
                onSend?()
                return
            }
        }
        super.keyDown(with: event)
    }

    override func paste(_ sender: Any?) {
        let files = PastedFiles.read(NSPasteboard.general)
        if !files.isEmpty {
            onPasteFiles?(files)
            return
        }
        pasteAsPlainText(sender)
    }

    /// Paste stays enabled for a pasteboard holding only an image, which a plain-text view would refuse.
    override func validateUserInterfaceItem(_ item: NSValidatedUserInterfaceItem) -> Bool {
        if item.action == #selector(paste(_:)), PastedFiles.available(NSPasteboard.general) { return true }
        return super.validateUserInterfaceItem(item)
    }

    override func setFrameSize(_ newSize: NSSize) {
        let widthChanged = newSize.width != frame.width
        super.setFrameSize(newSize)
        if widthChanged { onWidthChange?() }
    }
}

/**
 * What a paste attaches rather than types: files copied in Finder, or an
 * image (a screenshot, "Copy Image" in a browser) written to a PNG file.
 * Copied text that also carries a picture of itself, as a spreadsheet's
 * cells do, stays text.
 */
enum PastedFiles {
    static func available(_ pasteboard: NSPasteboard) -> Bool {
        !fileURLs(pasteboard).isEmpty || (!hasText(pasteboard) && pasteboard.canReadItem(withDataConformingToTypes: [UTType.image.identifier]))
    }

    static func read(_ pasteboard: NSPasteboard) -> [URL] {
        let files = fileURLs(pasteboard)
        if !files.isEmpty { return files }
        guard !hasText(pasteboard), let png = pngData(pasteboard) else { return [] }
        // Not the temporary folder: a draft can keep the image across days and restarts.
        let folder = Drafts.pastedFolder
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let stamp = Date().formatted(.verbatim("\(year: .defaultDigits)-\(month: .twoDigits)-\(day: .twoDigits) at \(hour: .twoDigits(clock: .twentyFourHour, hourCycle: .zeroBased)).\(minute: .twoDigits).\(second: .twoDigits)", timeZone: .current, calendar: .current))
        // Unique even for two pastes in one second: the seat saves each under its name.
        let url = folder.appendingPathComponent("Pasted image \(stamp) \(UUID().uuidString.prefix(4)).png")
        do {
            try png.write(to: url)
            return [url]
        } catch {
            return []
        }
    }

    private static func fileURLs(_ pasteboard: NSPasteboard) -> [URL] {
        (pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
    }

    private static func hasText(_ pasteboard: NSPasteboard) -> Bool {
        pasteboard.string(forType: .string)?.isEmpty == false
    }

    private static func pngData(_ pasteboard: NSPasteboard) -> Data? {
        if let png = pasteboard.data(forType: .png) { return png }
        guard let image = NSImage(pasteboard: pasteboard), let tiff = image.tiffRepresentation,
              let bitmap = NSBitmapImageRep(data: tiff) else { return nil }
        return bitmap.representation(using: .png, properties: [:])
    }
}
