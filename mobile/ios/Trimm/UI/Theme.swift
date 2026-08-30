import SwiftUI

extension Color {
    public static let ink = Color(hex: 0x09090B)
    public static let workspace = Color(hex: 0x18181B)
    public static let raised = Color(hex: 0x27272A)
    public static let border = Color(hex: 0x3F3F46)
    public static let cream = Color(hex: 0xFAFAFA)
    public static let creamRaised = Color(hex: 0xF4F4F5)
    public static let darkText = Color(hex: 0x09090B)
    public static let muted = Color(hex: 0xA1A1AA)
    public static let coral = Color(hex: 0xFAFAFA)
    public static let lavender = Color(hex: 0xD4D4D8)
    public static let green = Color(hex: 0xE4E4E7)
    public static let userBlue = Color(hex: 0x1B365D)
}

extension Color {
    init(hex: UInt, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xff) / 255,
            green: Double((hex >> 08) & 0xff) / 255,
            blue: Double((hex >> 00) & 0xff) / 255,
            opacity: alpha
        )
    }
}
