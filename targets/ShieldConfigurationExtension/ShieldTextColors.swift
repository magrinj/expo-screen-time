import Foundation

/// The shield's default text colours when the `ShieldConfig` sets a background
/// but leaves a text colour open: chosen for contrast against that background
/// rather than the system-appearance colours (`.label`, `.secondaryLabel`),
/// which know nothing about it (a dark `backgroundColor` in light mode used to
/// get black text). The same rule, with the same arithmetic, is the Android
/// `ShieldTextColors.kt`; `scripts/swift-tests/fixtures/shield-text-colors.tsv`
/// holds the cases both must agree on. Foundation only, so it compiles in the
/// extension and under `scripts/swift-tests/run.sh`.
///
/// - **Title**: pure white or pure black, whichever has the higher WCAG 2
///   contrast ratio against the background. One of the two always reaches at
///   least 4.58:1 against any opaque colour (the worst case is the mid grey where
///   they tie), so the title clears WCAG AA for normal text (4.5:1) by
///   construction.
/// - **Subtitle**: the title colour mixed into the background at 70%, the way
///   `secondaryLabel` is the label colour at reduced opacity, so it reads as
///   secondary; where that mix falls under 3:1 (saturated mid tones, mid greys)
///   it is made more opaque in 5% steps until it reaches 3:1, the AA floor for
///   large and secondary text. At 100% it is the title colour, so the floor
///   always holds.
/// - **Primary button label**: against the button's own background when the
///   config sets one, with or without a shield background.
/// - **Secondary button label**: a text-only button over the shield background,
///   so the title's colour.
/// - A colour the config sets is always kept, readable or not; a text colour
///   that does not parse counts as unset.
/// - No background set: nil, and the extension keeps today's system defaults.
///   Only labels the config gives a text to are drawn by the extension at all;
///   an element left out is the system's, text and colour.
/// - A translucent background (`#RRGGBBAA`) is judged by its RGB alone: what
///   shows through it (the blur over the blocked app) cannot be known here.
enum ShieldTextColors {
    /// An sRGB colour with alpha, as the config's `#RRGGBB` / `#RRGGBBAA` strings read.
    struct RGBA: Equatable {
        let red: UInt8
        let green: UInt8
        let blue: UInt8
        let alpha: UInt8

        static let white = RGBA(red: 255, green: 255, blue: 255, alpha: 255)
        static let black = RGBA(red: 0, green: 0, blue: 0, alpha: 255)

        /// `#RRGGBB` or `#RRGGBBAA`, the `#` optional, surrounding whitespace ignored; nil for anything else.
        init?(hex: String) {
            var digits = Substring(hex.trimmingCharacters(in: .whitespacesAndNewlines))
            if digits.hasPrefix("#") { digits = digits.dropFirst() }
            guard digits.count == 6 || digits.count == 8, digits.allSatisfy(\.isHexDigit),
                  let value = UInt64(digits, radix: 16) else { return nil }
            if digits.count == 6 {
                self.init(red: UInt8(value >> 16 & 0xFF), green: UInt8(value >> 8 & 0xFF), blue: UInt8(value & 0xFF), alpha: 255)
            } else {
                self.init(red: UInt8(value >> 24 & 0xFF), green: UInt8(value >> 16 & 0xFF), blue: UInt8(value >> 8 & 0xFF), alpha: UInt8(value & 0xFF))
            }
        }

        init(red: UInt8, green: UInt8, blue: UInt8, alpha: UInt8) {
            self.red = red
            self.green = green
            self.blue = blue
            self.alpha = alpha
        }

        /// `#RRGGBB`, or `#RRGGBBAA` when not opaque (the fixture's spelling).
        var hex: String {
            let rgb = String(format: "#%02X%02X%02X", red, green, blue)
            return alpha == 255 ? rgb : rgb + String(format: "%02X", alpha)
        }
    }

    /// The colours the extension draws with; nil keeps the system default for that element.
    struct Resolved: Equatable {
        let title: RGBA?
        let subtitle: RGBA?
        let primaryButton: RGBA?
        let secondaryButton: RGBA?
    }

    /// AA for normal text, the title's bar.
    static let titleMinContrast = 4.5

    /// AA for large text, the subtitle's bar.
    static let subtitleMinContrast = 3.0

    /// The rule (see the type comment), on the config's parsed colours; a set colour passes through.
    static func resolve(
        background: RGBA?,
        title: RGBA?,
        subtitle: RGBA?,
        primaryButtonBackground: RGBA?,
        primaryButton: RGBA?,
        secondaryButton: RGBA?
    ) -> Resolved {
        return Resolved(
            title: title ?? background.map(onBackground),
            subtitle: subtitle ?? background.map(secondaryOn),
            primaryButton: primaryButton ?? primaryButtonBackground.map(onBackground),
            secondaryButton: secondaryButton ?? background.map(onBackground)
        )
    }

    /// White or black, whichever contrasts more with `background` (its alpha ignored).
    static func onBackground(_ background: RGBA) -> RGBA {
        return contrast(.white, background) >= contrast(.black, background) ? .white : .black
    }

    /// The secondary text colour over `background`: the title colour mixed in at 70%, more if 3:1 needs it.
    static func secondaryOn(_ background: RGBA) -> RGBA {
        let text = onBackground(background)
        for percent in stride(from: 70, through: 100, by: 5) {
            let mixed = mix(text, background, percent: percent)
            if contrast(mixed, background) >= subtitleMinContrast { return mixed }
        }
        return text
    }

    /// WCAG 2 contrast ratio between two colours, alpha ignored: 1 (same) to 21 (black on white).
    static func contrast(_ a: RGBA, _ b: RGBA) -> Double {
        let la = relativeLuminance(a)
        let lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// WCAG 2 relative luminance of an sRGB colour, 0 (black) to 1 (white).
    static func relativeLuminance(_ color: RGBA) -> Double {
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }

    private static func linear(_ channel: UInt8) -> Double {
        let c = Double(channel) / 255.0
        return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
    }

    /// `percent` of `foreground` over the rest of `background`, per channel, in integers so both platforms round alike; opaque.
    private static func mix(_ foreground: RGBA, _ background: RGBA, percent: Int) -> RGBA {
        func channel(_ f: UInt8, _ b: UInt8) -> UInt8 {
            return UInt8((percent * Int(f) + (100 - percent) * Int(b) + 50) / 100)
        }
        return RGBA(
            red: channel(foreground.red, background.red),
            green: channel(foreground.green, background.green),
            blue: channel(foreground.blue, background.blue),
            alpha: 255
        )
    }
}
