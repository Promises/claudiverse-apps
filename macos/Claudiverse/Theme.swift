import SwiftUI

/// The Android app's palette: Nord for status, the web's violet for the accent. The rest is the system's.
enum Theme {
    static let accent = Color(light: 0x7C3AED, dark: 0xA78BFA)

    static let good = Color(hex: 0xA3BE8C)
    static let busy = Color(hex: 0xEBCB8B)
    static let bad = Color(hex: 0xBF616A)
    static let muted = Color(hex: 0x96A0B2)
    static let info = Color(hex: 0x88C0D0)
    /// A seat asking for the operator.
    static let attention = Color(hex: 0xD08770)
    /// Text on a status colour.
    static let onStatus = Color(hex: 0x2E3440)

    static let userBubble = Color(light: 0xEDE9FE, dark: 0x463A6B)
    /// Material's surfaceContainerHigh for the violet scheme, the colour of Claude's messages on the phone.
    static let claudeBubble = Color(light: 0xECE6F0, dark: 0x2B2930)
    /// Claude's thinking: a step quieter than its replies.
    static let thinkingBubble = Color(light: 0xF5F2F7, dark: 0x232129)
    static let codeBackground = Color(light: 0xECEFF4, dark: 0x272C36)
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }

    init(light: UInt32, dark: UInt32) {
        self.init(nsColor: NSColor(name: nil) { appearance in
            let hex = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
            return NSColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        })
    }
}
