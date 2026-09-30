import Foundation

/// One owner for synchronous libmpv calls, including initialization and final destruction.
/// Closing never waits for that queue. In-flight work finishes before destruction; queued work
/// and late UI publications are discarded. Each controller owns an independent executor.
final class MPVSerialExecutor {
    private let queue = DispatchQueue(label: "com.nuvio.mpv.session", qos: .userInitiated)
    private let queueKey = DispatchSpecificKey<Bool>()
    private let lock = NSLock()
    private var closed = false

    init() { queue.setSpecific(key: queueKey, value: true) }

    var isOnQueue: Bool { DispatchQueue.getSpecific(key: queueKey) == true }
    var isOpen: Bool {
        lock.lock()
        defer { lock.unlock() }
        return !closed
    }

    func perform(_ work: @escaping () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        guard !closed else { return }
        queue.async { [self] in
            guard isOpen else { return }
            work()
        }
    }

    func performAfter(_ delay: TimeInterval, _ work: @escaping () -> Void) {
        queue.asyncAfter(deadline: .now() + delay) { [self] in
            guard isOpen else { return }
            work()
        }
    }

    @discardableResult
    func close(_ destroy: @escaping () -> Void) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !closed else { return false }
        closed = true
        // Retains the context's owner until all previous calls have returned. Never queue.sync.
        queue.async(execute: destroy)
        return true
    }
}
