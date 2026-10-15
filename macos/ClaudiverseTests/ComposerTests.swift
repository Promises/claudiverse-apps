import AppKit
import XCTest
@testable import Claudiverse

/// The message box's keys and paste: what sends, what starts a line, and what a paste attaches.
final class ComposerTests: XCTestCase {
    private var pasteboard: NSPasteboard!

    override func setUp() {
        pasteboard = NSPasteboard(name: NSPasteboard.Name("ComposerTests-\(UUID().uuidString)"))
        pasteboard.clearContents()
    }

    override func tearDown() {
        pasteboard.releaseGlobally()
    }

    private func png() -> Data {
        let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 4, pixelsHigh: 4, bitsPerSample: 8, samplesPerPixel: 4,
                                      hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        return bitmap.representation(using: .png, properties: [:])!
    }

    // MARK: paste

    /// A screenshot copied with ⌃⇧⌘4 is an image and nothing else: it becomes a PNG file.
    func testAnImageAloneBecomesAPNGFile() throws {
        pasteboard.setData(png(), forType: .png)
        XCTAssertTrue(PastedFiles.available(pasteboard))
        let files = PastedFiles.read(pasteboard)
        XCTAssertEqual(files.count, 1)
        XCTAssertEqual(files[0].pathExtension, "png")
        XCTAssertNotNil(NSImage(contentsOf: files[0]), "the file holds the pasted image")
    }

    /// A TIFF (what many apps put on the pasteboard) is converted, not written as-is.
    func testATIFFImageIsWrittenAsPNG() throws {
        pasteboard.setData(NSImage(data: png())!.tiffRepresentation!, forType: .tiff)
        let file = try XCTUnwrap(PastedFiles.read(pasteboard).first)
        let bytes = try Data(contentsOf: file)
        XCTAssertEqual(Array(bytes.prefix(4)), [0x89, 0x50, 0x4E, 0x47])
    }

    /// Spreadsheet cells come with a picture of themselves; the operator wants the text.
    func testTextWithAPictureOfItselfStaysText() {
        pasteboard.declareTypes([.string, .png], owner: nil)
        pasteboard.setString("a\tb", forType: .string)
        pasteboard.setData(png(), forType: .png)
        XCTAssertFalse(PastedFiles.available(pasteboard))
        XCTAssertEqual(PastedFiles.read(pasteboard), [])
    }

    func testFilesCopiedInFinderAreAttachedAsThemselves() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("composer-test-\(UUID().uuidString).log")
        try Data("x".utf8).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        pasteboard.writeObjects([file as NSURL])
        XCTAssertEqual(PastedFiles.read(pasteboard).map(\.standardizedFileURL), [file.standardizedFileURL])
    }

    func testPlainTextAttachesNothing() {
        pasteboard.setString("hello", forType: .string)
        XCTAssertFalse(PastedFiles.available(pasteboard))
        XCTAssertEqual(PastedFiles.read(pasteboard), [])
    }

    // MARK: keys

    private func returnKey(_ modifiers: NSEvent.ModifierFlags) -> NSEvent {
        NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: modifiers, timestamp: 0, windowNumber: 0,
                         context: nil, characters: "\r", charactersIgnoringModifiers: "\r", isARepeat: false, keyCode: 36)!
    }

    private func textView(sent: @escaping () -> Void) -> ComposerTextView {
        let view = ComposerTextView(frame: NSRect(x: 0, y: 0, width: 200, height: 20))
        view.isRichText = false
        view.string = "line"
        view.setSelectedRange(NSRange(location: 4, length: 0))
        view.onSend = sent
        return view
    }

    func testReturnSends() {
        var sends = 0
        let view = textView { sends += 1 }
        view.keyDown(with: returnKey([]))
        XCTAssertEqual(sends, 1)
        XCTAssertEqual(view.string, "line", "Return does not also type a new line")
    }

    /// ⌘⇧Return sends now (interrupting the turn), not a plain send.
    func testCommandShiftReturnSendsNow() {
        var sends = 0, now = 0
        let view = textView { sends += 1 }
        view.onSendNow = { now += 1 }
        view.keyDown(with: returnKey([.command, .shift]))
        XCTAssertEqual(now, 1)
        XCTAssertEqual(sends, 0)
        XCTAssertEqual(view.string, "line")
    }

    func testShiftAndOptionReturnStartANewLine() {
        var sends = 0
        for modifiers in [NSEvent.ModifierFlags.shift, .option] {
            let view = textView { sends += 1 }
            view.keyDown(with: returnKey(modifiers))
            XCTAssertEqual(view.string, "line\n", "\(modifiers)")
        }
        XCTAssertEqual(sends, 0)
    }
}
