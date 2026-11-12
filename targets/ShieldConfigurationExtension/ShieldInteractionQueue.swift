import Foundation

/// The queue behind `drainShieldInteractions()`: the rules, without the App
/// Group.
///
/// The shield action extension records one entry per press of a shield
/// button — its handler is the one callback that reliably fires per shield
/// interaction (the configuration data source is cached by the system) — and
/// the app reads-and-clears the queue to power "you were blocked N times"
/// UIs. Each entry carries what was shielded (`target`, the handle
/// `getTokens` returns for that app, category or web domain) and the
/// monitored activity whose source owns the shield (`activityName`, nil for
/// the manual block).
///
/// Presses on the SAME target within `collapseWindowMs` are one entry: a
/// user tapping both buttons of one shield is one blocked attempt, not two.
/// Presses on different targets within the window are separate entries, so
/// two apps opened back to back count twice. The last press per target
/// survives a drain, so a press right after one still collapses. The queue is capped at `capacity`:
/// it only grows while the app is not draining it.
///
/// Entries written by builds before `target` and `activityName` existed
/// still drain, with both fields `null`.
///
/// Pure Foundation on purpose, unit-tested in `scripts/swift-tests`. Copied
/// verbatim into every extension target — keep the copies byte-identical.
enum ShieldInteractionQueue {
    /// Two presses on one target closer than this are one blocked attempt.
    static let collapseWindowMs: Double = 2_000
    /// Entries kept while the app is not draining; the oldest go first.
    static let capacity = 200

    /// What a shield covered, as JS addresses tokens: a `TokenHandle`.
    struct Target: Equatable {
        /// `application`, `category` or `webDomain`.
        var type: String
        /// The handle `getTokens` returns for this token (`SelectionStore`
        /// derives both from the token's encoded form), so `<TokenLabel>`
        /// renders it. Opaque to JS.
        var handle: String

        init(type: String, handle: String) {
            self.type = type
            self.handle = handle
        }

        init?(dictionary: [String: Any]) {
            guard let type = dictionary["type"] as? String, !type.isEmpty,
                  let handle = dictionary["handle"] as? String, !handle.isEmpty else {
                return nil
            }
            self.type = type
            self.handle = handle
        }

        var dictionary: [String: Any] {
            return ["handle": handle, "type": type]
        }

        /// What "the same target" means for the collapse.
        fileprivate var key: String {
            return type + ":" + handle
        }
    }

    /// One press, as persisted and as returned to JS.
    struct Entry: Equatable {
        /// `primary` or `secondary`.
        var button: String
        /// Unix milliseconds.
        var timestamp: Double
        /// nil when no handle could be derived for the pressed token.
        var target: Target?
        /// The user-facing name of the activity whose source shields the
        /// target; nil for the manual block or when nothing owns it.
        var activityName: String?

        init(button: String, timestamp: Double, target: Target? = nil, activityName: String? = nil) {
            self.button = button
            self.timestamp = timestamp
            self.target = target
            self.activityName = Self.userFacing(activityName: activityName)
        }

        /// The persisted shape. Entries of builds before `target` and
        /// `activityName` have neither; anything without a button and a
        /// timestamp never was an entry.
        init?(dictionary: [String: Any]) {
            guard let button = dictionary["button"] as? String, !button.isEmpty,
                  let timestamp = Self.double(dictionary["timestamp"]) else {
                return nil
            }
            self.button = button
            self.timestamp = timestamp
            self.target = (dictionary["target"] as? [String: Any]).flatMap(Target.init(dictionary:))
            self.activityName = Self.userFacing(activityName: dictionary["activityName"] as? String)
        }

        /// Property-list shape, for the App Group; nil fields are absent.
        var dictionary: [String: Any] {
            var dictionary: [String: Any] = ["button": button, "timestamp": timestamp]
            if let target = target {
                dictionary["target"] = target.dictionary
            }
            if let activityName = activityName {
                dictionary["activityName"] = activityName
            }
            return dictionary
        }

        /// The `ShieldInteraction` JS receives: every field present, nil
        /// fields as JSON `null` (not `undefined`).
        var interaction: [String: Any] {
            return [
                "button": button,
                "timestamp": timestamp,
                "target": target?.dictionary ?? NSNull(),
                "activityName": activityName ?? NSNull(),
            ]
        }

        /// The module's reserved names (`expo_st_…`: the unlock and snooze
        /// meters) never reach JS as an activity; nor does an empty name.
        static func userFacing(activityName: String?) -> String? {
            guard let name = activityName, !name.isEmpty, !name.hasPrefix("expo_st_") else { return nil }
            return name
        }

        private static func double(_ value: Any?) -> Double? {
            if let double = value as? Double { return double }
            if let number = value as? NSNumber { return number.doubleValue }
            return nil
        }
    }

    /// The whole persisted state: the entries not yet drained, and the last
    /// recorded press per target — kept across drains, pruned to the window.
    struct State: Equatable {
        var entries: [Entry] = []
        /// Target key → Unix ms of the last press that was recorded for it.
        var lastPressByTarget: [String: Double] = [:]

        init(entries: [Entry] = [], lastPressByTarget: [String: Double] = [:]) {
            self.entries = entries
            self.lastPressByTarget = lastPressByTarget
        }

        /// Records `entry` unless it collapses into a press on the same
        /// target less than `collapseWindowMs` earlier. Either way the
        /// per-target record is pruned to the window around `entry.timestamp`
        /// — on both sides, so a record the clock has since jumped back
        /// behind does not linger (so it stays bounded by the number of
        /// distinct targets pressed in the last two seconds). Returns whether
        /// the entry was kept.
        @discardableResult
        mutating func record(_ entry: Entry) -> Bool {
            let now = entry.timestamp
            let key = entry.target?.key ?? ""
            let last = lastPressByTarget[key]
            lastPressByTarget = lastPressByTarget.filter { abs(now - $0.value) < ShieldInteractionQueue.collapseWindowMs }

            if let last = last, now >= last, now - last < ShieldInteractionQueue.collapseWindowMs {
                return false
            }
            entries.append(entry)
            if entries.count > ShieldInteractionQueue.capacity {
                entries.removeFirst(entries.count - ShieldInteractionQueue.capacity)
            }
            lastPressByTarget[key] = now
            return true
        }

        /// Every entry, oldest first, in the JS shape; the queue is empty
        /// afterwards. The per-target record stays, so the collapse spans
        /// the drain.
        mutating func drain() -> [[String: Any]] {
            let interactions = entries.map(\.interaction)
            entries = []
            return interactions
        }
    }

    /// The entries of a persisted queue — a JSON array of `Entry.dictionary`
    /// (the layout every build has used). Entries this build cannot read
    /// are dropped.
    static func entries(fromJSON json: String?) -> [Entry] {
        guard let json = json,
              let data = json.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return parsed.compactMap(Entry.init(dictionary:))
    }

    /// `entries` as the persisted JSON array; nil for an empty queue.
    static func json(of entries: [Entry]) -> String? {
        guard !entries.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: entries.map(\.dictionary)),
              let json = String(data: data, encoding: .utf8) else {
            return nil
        }
        return json
    }
}
