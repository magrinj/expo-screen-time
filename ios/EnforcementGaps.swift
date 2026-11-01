import Foundation

/// A period during which blocks were intended but not enforced
/// (`getEnforcementGaps`). Pure Foundation so `scripts/swift-tests` covers it.
struct EnforcementGap: Codable, Equatable {
    /// Unix ms.
    var from: Double
    /// Unix ms; `nil` while the gap is still open.
    var to: Double?
    /// One of the contract's reasons. iOS only knows `authorizationRevoked`.
    var reason: String

    var dictionary: [String: Any] {
        return ["from": from, "to": to.map { $0 as Any } ?? NSNull(), "reason": reason]
    }
}

/// Screen Time authorization over time, folded from observations (launch,
/// foreground, `getEnforcementGaps`).
///
/// When access is revoked (Settings › Screen Time, or the app's access turned
/// off), iOS lifts every shield and stops the app's monitoring: nothing the
/// app set is enforced until access is granted again. The revocation itself is
/// not observable, only its effect at the next observation, so a gap opens at
/// the last time access was seen granted (the revocation happened between
/// that and the first observation without it) and closes at the first
/// observation with access back.
struct AuthorizationGapTracker: Codable, Equatable {
    static let reason = "authorizationRevoked"
    /// Oldest closed gaps are dropped past this.
    static let maxGaps = 50

    /// The last time access was observed granted, Unix ms. `nil` until the
    /// first grant: never having had access is not a gap.
    var lastApprovedAt: Double?
    var gaps: [EnforcementGap] = []

    var hasOpenGap: Bool {
        return gaps.last.map { $0.to == nil } ?? false
    }

    /// Fold in one observation of the authorization status at `now` (Unix ms).
    mutating func observe(approved: Bool, at now: Double) {
        if approved {
            if hasOpenGap {
                gaps[gaps.count - 1].to = now
            }
            lastApprovedAt = now
            return
        }
        guard let since = lastApprovedAt, !hasOpenGap else { return }
        gaps.append(EnforcementGap(from: since, to: nil, reason: Self.reason))
        if gaps.count > Self.maxGaps {
            gaps.removeFirst(gaps.count - Self.maxGaps)
        }
    }

    /// The gaps still in progress at, or ending after, `since` (Unix ms);
    /// every gap when `since` is `nil`. Oldest first.
    func gaps(since: Double?) -> [EnforcementGap] {
        guard let since = since else { return gaps }
        return gaps.filter { ($0.to ?? .infinity) >= since }
    }
}
