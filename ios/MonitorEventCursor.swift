import Foundation

/// Decides which stored monitor events still have to be emitted to JS.
///
/// The monitor extension appends events to the App Group log; the module reads
/// that log in bursts (Darwin notification, or the 2 s fallback poll), so a
/// single read can surface several new events. Every one of them must be
/// emitted exactly once, oldest first — including events that share a
/// millisecond timestamp. Pure Foundation on purpose: the logic is testable
/// without a device.
struct MonitorEventCursor {
    /// Events strictly older than this were handled by earlier reads.
    private(set) var timestamp: Double
    /// Keys already emitted at exactly `timestamp` (ties at the cursor).
    private var emittedAtCursor: Set<String> = []

    init(startingAt timestamp: Double) {
        self.timestamp = timestamp
    }

    /// Returns the events not yet emitted, oldest first, and advances past
    /// them. `events` is the log as read, in any order.
    mutating func take(from events: [[String: Any]]) -> [[String: Any]] {
        let fresh = events.enumerated()
            .compactMap { offset, event -> (timestamp: Double, offset: Int, key: String, event: [String: Any])? in
                guard let timestamp = event["timestamp"] as? Double, timestamp >= self.timestamp else {
                    return nil
                }
                let key = Self.key(of: event)
                if timestamp == self.timestamp && emittedAtCursor.contains(key) { return nil }
                return (timestamp, offset, key, event)
            }
            // The log is read newest-first: for equal timestamps the later
            // offset was written first.
            .sorted { $0.timestamp != $1.timestamp ? $0.timestamp < $1.timestamp : $0.offset > $1.offset }

        guard let newest = fresh.last?.timestamp else { return [] }
        if newest > timestamp {
            timestamp = newest
            emittedAtCursor = []
        }
        for entry in fresh where entry.timestamp == newest {
            emittedAtCursor.insert(entry.key)
        }
        return fresh.map(\.event)
    }

    /// Stable identity of a stored event: its `id`, or — for events written
    /// before ids existed — its content.
    static func key(of event: [String: Any]) -> String {
        if let id = event["id"] as? String { return id }
        let activityName = event["activityName"] as? String ?? ""
        let eventType = event["eventType"] as? String ?? ""
        let eventName = event["eventName"] as? String ?? ""
        let timestamp = event["timestamp"] as? Double ?? 0
        return "\(activityName)|\(eventType)|\(eventName)|\(timestamp)"
    }
}
