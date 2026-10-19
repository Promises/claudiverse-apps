import AppKit
import SwiftUI
import XCTest
@testable import Claudiverse

/// The themes: what AppKit draws in for each, and the colours each gives.
final class ThemeTests: XCTestCase {
    override func tearDown() {
        ThemeChoice.active = .system
        super.tearDown()
    }

    /// A colour as it draws in [appearance], as 0xRRGGBB.
    private func hex(_ color: Color, in appearance: NSAppearance.Name) -> UInt32 {
        var out: UInt32 = 0
        NSAppearance(named: appearance)!.performAsCurrentDrawingAppearance {
            let c = NSColor(color).usingColorSpace(.sRGB)!
            out = UInt32((c.redComponent * 255).rounded()) << 16 | UInt32((c.greenComponent * 255).rounded()) << 8
                | UInt32((c.blueComponent * 255).rounded())
        }
        return out
    }

    func testEachThemeDrawsItsControlsInTheRightAppearance() {
        XCTAssertNil(ThemeChoice.system.appearance, "Automatic follows macOS")
        XCTAssertEqual(ThemeChoice.light.appearance?.name, .aqua)
        XCTAssertEqual(ThemeChoice.dark.appearance?.name, .darkAqua)
        XCTAssertEqual(ThemeChoice.nord.appearance?.name, .darkAqua, "Nord is a dark theme")
        XCTAssertEqual(ThemeChoice.allCases.map(\.label), ["Automatic", "Light", "Dark", "Nord"])
    }

    /// Light and dark as the appearance is; Nord's own colour under Nord, whatever the appearance.
    func testColoursFollowTheTheme() {
        ThemeChoice.active = .system
        XCTAssertEqual(hex(Theme.accent, in: .aqua), 0x7C3AED)
        XCTAssertEqual(hex(Theme.accent, in: .darkAqua), 0xA78BFA)
        ThemeChoice.active = .nord
        XCTAssertEqual(hex(Theme.accent, in: .darkAqua), Nord.frost1)
        XCTAssertEqual(hex(Theme.claudeBubble, in: .darkAqua), Nord.polar1)
        XCTAssertEqual(hex(Theme.codeBackground, in: .aqua), Nord.polarDeep)
    }

    /// Nothing saved, or something unknown, is Automatic.
    func testAnUnknownSavedThemeIsAutomatic() {
        XCTAssertEqual(ThemeChoice.saved { _ in nil }, .system)
        XCTAssertEqual(ThemeChoice.saved { $0 == ThemeChoice.key ? "nord" : nil }, .nord)
        XCTAssertEqual(ThemeChoice.saved { _ in "solarized" }, .system)
    }
}
