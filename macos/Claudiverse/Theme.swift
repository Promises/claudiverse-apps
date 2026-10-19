import AppKit
import SwiftUI

/**
 * The app's look, picked in Settings → Appearance: the system's light or
 * dark as macOS has it (Automatic), always light, always dark, or Nord
 * (https://www.nordtheme.com): dark, with Nord's backgrounds, text and accent.
 */
enum ThemeChoice: String, CaseIterable, Identifiable {
    case system, light, dark, nord

    static let key = "theme"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: "Automatic"
        case .light: "Light"
        case .dark: "Dark"
        case .nord: "Nord"
        }
    }

    /// What AppKit draws its controls in: nil follows macOS.
    var appearance: NSAppearance? {
        switch self {
        case .system: nil
        case .light: NSAppearance(named: .aqua)
        case .dark, .nord: NSAppearance(named: .darkAqua)
        }
    }

    /// The saved choice ([read]: the defaults, or a test's stand-in); nothing or something unknown is Automatic.
    static func saved(_ read: (String) -> String? = { UserDefaults.standard.string(forKey: $0) }) -> ThemeChoice {
        read(key).flatMap(ThemeChoice.init(rawValue:)) ?? .system
    }

    /// The one in use, read by the colours as they draw; set by [Themed] when it changes.
    nonisolated(unsafe) static var active = saved()
}

/// Nord's palette: Polar Night (backgrounds), Snow Storm (text), Frost (accents), Aurora (status).
enum Nord {
    static let polar0: UInt32 = 0x2E3440, polar1: UInt32 = 0x3B4252, polar2: UInt32 = 0x434C5E, polar3: UInt32 = 0x4C566A
    /// A step below polar0, for sidebars: the darker frame many Nord ports use.
    static let polarDeep: UInt32 = 0x292E39
    static let snow0: UInt32 = 0xD8DEE9, snow2: UInt32 = 0xECEFF4
    static let frost1: UInt32 = 0x88C0D0, frost3: UInt32 = 0x5E81AC
}

/// The Android app's palette: Nord for status, the web's violet for the accent; the rest is the system's, or Nord's.
enum Theme {
    static let accent = Color(light: 0x7C3AED, dark: 0xA78BFA, nord: Nord.frost1)

    static let good = Color(hex: 0xA3BE8C)
    static let busy = Color(hex: 0xEBCB8B)
    static let bad = Color(hex: 0xBF616A)
    static let muted = Color(hex: 0x96A0B2)
    static let info = Color(hex: 0x88C0D0)
    /// A seat asking for the operator.
    static let attention = Color(hex: 0xD08770)
    /// Text on a status colour.
    static let onStatus = Color(hex: 0x2E3440)

    static let userBubble = Color(light: 0xEDE9FE, dark: 0x463A6B, nord: Nord.polar3)
    /// Material's surfaceContainerHigh for the violet scheme, the colour of Claude's messages on the phone.
    static let claudeBubble = Color(light: 0xECE6F0, dark: 0x2B2930, nord: Nord.polar1)
    /// Claude's thinking: a step quieter than its replies.
    static let thinkingBubble = Color(light: 0xF5F2F7, dark: 0x232129, nord: 0x353B48)
    static let codeBackground = Color(light: 0xECEFF4, dark: 0x272C36, nord: Nord.polarDeep)

    /// Nord's own backgrounds and text; the other looks keep the system's (see [Themed]).
    static let nordWindow = Color(hex: Nord.polar0)
    static let nordSidebar = Color(hex: Nord.polarDeep)
    static let nordBar = Color(hex: Nord.polar1)
    static let nordText = Color(hex: Nord.snow2)
}

/**
 * A window's content in the chosen look: AppKit's appearance (for controls),
 * the accent, and under Nord its background, text and toolbar. A new choice
 * redraws the window from scratch (its identity changes), since a colour's
 * look is otherwise only redrawn when the appearance itself changes.
 */
struct Themed: ViewModifier {
    @AppStorage(ThemeChoice.key) private var stored = ThemeChoice.system.rawValue

    func body(content: Content) -> some View {
        let theme = ThemeChoice(rawValue: stored) ?? .system
        ThemeChoice.active = theme
        return Group {
            if theme == .nord {
                content
                    .foregroundStyle(Theme.nordText)
                    .background(Theme.nordWindow)
                    .toolbarBackground(Theme.nordBar, for: .windowToolbar)
                    .toolbarBackground(.visible, for: .windowToolbar)
            } else {
                content
            }
        }
        .tint(Theme.accent)
        .id(theme)
        .onAppear { NSApp.appearance = theme.appearance }
        .onChange(of: stored) { _, new in NSApp.appearance = (ThemeChoice(rawValue: new) ?? .system).appearance }
    }
}

extension View {
    /// The chosen look; see [Themed]. On each window's root.
    func themed() -> some View { modifier(Themed()) }

    /// A bar (composer, notices) in the chosen look: the system's bar material, or Nord's bar colour.
    @ViewBuilder func themedBar() -> some View {
        if ThemeChoice.active == .nord { background(Theme.nordBar) } else { background(.bar) }
    }

    /// A sidebar list or grouped form: Nord paints its own background under it; the other looks keep the system's.
    @ViewBuilder func themedList(sidebar: Bool = false) -> some View {
        if ThemeChoice.active == .nord {
            scrollContentBackground(.hidden).background(sidebar ? Theme.nordSidebar : Theme.nordWindow)
        } else {
            self
        }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }

    /// Light or dark as the appearance is; [nord] under the Nord theme (dark when not given).
    init(light: UInt32, dark: UInt32, nord: UInt32? = nil) {
        self.init(nsColor: NSColor(name: nil) { appearance in
            let hex = ThemeChoice.active == .nord ? (nord ?? dark)
                : appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
            return NSColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        })
    }
}
