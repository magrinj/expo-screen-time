import Foundation

/// The one mutex over the block state: the App Group records (interval
/// markers, threshold lists, monitoring configs and their generations, the
/// store registry, the unlock grant, the shield owners) and the named
/// `ManagedSettingsStore`s projected from them.
///
/// Why one lock: the app, the monitor extension, the shield configuration
/// extension and the shield action extension are four processes, and each of
/// them reads the state, decides, then writes the state and the stores.
/// Without coordination every such read-decide-write races the others. Seen
/// in review: an old registration's `intervalDidEnd` passed its generation
/// check, was pre-empted, then cleared the marker and the store the NEW
/// generation had just written; a store emptied by one process was dropped
/// from the registry after another process had filled it again; a grant
/// captured the sources to suspend, then emptied every store — including the
/// one a schedule had started in between. Patching each interleaving leaves
/// the next one open; serializing the critical sections closes the class.
///
/// How: an exclusive `flock(2)` on a file in the App Group container, the one
/// path every process can open. The kernel releases the lock when the
/// holder's descriptor closes — including when the process dies, which
/// short-lived extensions do without notice, so a crash never wedges the
/// others. Inside a process a recursive lock serializes threads and allows
/// nesting: only the outermost `withLock` takes and releases the file lock,
/// so a critical section may call helpers that take the lock themselves.
///
/// Rules for every critical section:
/// - Every read-decide-write over block state runs inside `withLock`, and the
///   decision (the generation check, "is this store empty now", "what does the
///   grant suspend") is made in the SAME critical section as the writes it
///   guards. A check made before taking the lock proves nothing.
/// - Short and synchronous: no waiting on another queue, no sleeping, no
///   dispatching back to the main thread and waiting. Extensions run on a
///   time budget and every process waits on the slowest holder.
/// - NOT held across `DeviceActivityCenter.startMonitoring` / `stopMonitoring`
///   / `activities`. Those are XPC calls into the system daemon, and the
///   daemon dispatches the resulting callbacks to the monitor extension — a
///   process that takes this very lock. The daemon is not known to wait for
///   the extension before replying, but if it ever did, holding the lock
///   across the call would deadlock the app, the daemon and the extension.
///   So the state is put in its final form under the lock FIRST, the lock is
///   released, and only then is the system told. A callback the system then
///   triggers takes the lock, finds the final state and lets its generation
///   check decide (`ActivityRegistration.currentRegistration`). The window
///   this leaves is the failed-registration rollback, documented there.
/// - No `ManagedSettingsStore` write outside the lock. The stores are a
///   projection of the state (`BlockComposition.storeShields`), recomputed
///   from the whole state inside the lock — never "clear everything, then
///   re-apply what I remember". A store leaves the registry only when that
///   recompute, under the lock, says it holds nothing.
///
/// Pure Foundation (`flock` comes from Darwin's libc), so it compiles under
/// plain `swiftc` for `scripts/swift-tests`, where two threads and two
/// processes are shown to serialize. Copied verbatim into every extension
/// target — keep the copies byte-identical.
final class BlockStateLock {
    /// The lock file's name inside the App Group container.
    static let fileName = "expo_st_block_state.lock"

    private let fileURL: URL?
    private let threads = NSRecursiveLock()
    /// Nesting depth of the thread holding `threads`. Only touched while it is held.
    private var depth = 0
    private var descriptor: Int32 = -1

    /// A lock over `fileURL`. `nil` degrades to in-process serialization only
    /// (no App Group container, i.e. misconfigured entitlements — the same
    /// condition under which the App Group state is not shared either).
    init(fileURL: URL?) {
        self.fileURL = fileURL
    }

    /// The lock every process of the app shares: the file in the App Group
    /// container (`group.<bundle id>`).
    static func inAppGroupContainer(_ groupIdentifier: String?) -> BlockStateLock {
        guard let groupIdentifier = groupIdentifier,
              let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupIdentifier) else {
            NSLog("[expo-screen-time] BlockStateLock: no App Group container for %@; block state is serialized within this process only", groupIdentifier ?? "nil")
            return BlockStateLock(fileURL: nil)
        }
        return BlockStateLock(fileURL: container.appendingPathComponent(fileName))
    }

    /// Run `body` as one critical section, exclusive across the threads of
    /// this process and across processes. Re-entrant: a nested call on the
    /// thread that holds the lock runs at once, and the file lock is released
    /// when the outermost call returns — normally or by throwing.
    func withLock<T>(_ body: () throws -> T) rethrows -> T {
        threads.lock()
        defer { threads.unlock() }
        if depth == 0 {
            acquireFile()
        }
        depth += 1
        defer {
            depth -= 1
            if depth == 0 {
                releaseFile()
            }
        }
        return try body()
    }

    // MARK: - File lock

    /// Open (creating if needed) and `flock` the file. Blocks until the lock
    /// is free; the holder's death frees it. Call with `threads` held and
    /// `depth == 0`.
    private func acquireFile() {
        guard let fileURL = fileURL else { return }
        let fd = open(fileURL.path, O_RDWR | O_CREAT | O_CLOEXEC, 0o644)
        guard fd >= 0 else {
            warnDegraded("open", errno)
            return
        }
        #if os(iOS)
        // The file must be openable whenever an extension runs, locked device
        // included; it holds no data.
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.none], ofItemAtPath: fileURL.path)
        #endif
        while flock(fd, LOCK_EX) != 0 {
            if errno == EINTR { continue }
            // Cannot lock the file (never seen on an App Group container):
            // degrade to in-process serialization rather than refuse to work.
            warnDegraded("flock", errno)
            close(fd)
            return
        }
        descriptor = fd
    }

    /// Whether the degraded-mode warning was logged. Only touched with `threads` held.
    private var warnedDegraded = false

    /// Say once, loudly, that cross-process serialization is off.
    private func warnDegraded(_ call: String, _ code: Int32) {
        guard !warnedDegraded else { return }
        warnedDegraded = true
        NSLog("[expo-screen-time] BlockStateLock: %@ failed (errno %d); block state is serialized within this process only", call, code)
    }

    /// Call with `threads` held, once the outermost critical section ends.
    private func releaseFile() {
        guard descriptor >= 0 else { return }
        flock(descriptor, LOCK_UN)
        close(descriptor)
        descriptor = -1
    }
}
