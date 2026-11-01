import Foundation

// Two-process probe for BlockStateLock: `run.sh` starts two instances on the
// same lock file and counter file. Each instance repeats a read-modify-write
// of the counter under the lock; the window between the read and the write is
// widened so that, without the lock, lost updates are near certain. The
// counter must end at 2 × iterations.
//
//   lockprobe <lock file> <counter file> <iterations> [nolock]
//
// `nolock` runs the same loop without the lock, to show what the probe detects.

let arguments = CommandLine.arguments
guard arguments.count >= 4, let iterations = Int(arguments[3]) else {
    FileHandle.standardError.write("usage: lockprobe <lock file> <counter file> <iterations> [nolock]\n".data(using: .utf8)!)
    exit(2)
}
let lock = BlockStateLock(fileURL: URL(fileURLWithPath: arguments[1]))
let counterURL = URL(fileURLWithPath: arguments[2])
let useLock = arguments.count < 5 || arguments[4] != "nolock"

func readModifyWrite() {
    let text = (try? String(contentsOf: counterURL, encoding: .utf8)) ?? "0"
    let current = Int(text.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0
    usleep(100)
    try! String(current + 1).write(to: counterURL, atomically: true, encoding: .utf8)
}

for _ in 0..<iterations {
    if useLock {
        lock.withLock(readModifyWrite)
    } else {
        readModifyWrite()
    }
}
