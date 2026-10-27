import Foundation

/// Kotlin's synchronous media-info getter reads this snapshot; all mpv reads stay on its serial executor.
final class PlayerMediaPropertyCache {
    private let lock = NSLock()
    private var values: [String: String] = [:]
    private var lastRead: TimeInterval = -.infinity
    private static let names = [
        "current-tracks/video/codec", "video-params/w", "video-params/h", "container-fps",
        "video-bitrate", "current-tracks/audio/codec", "audio-params/channel-count", "audio-params/samplerate"
    ]

    func value(for name: String) -> String {
        lock.lock()
        defer { lock.unlock() }
        return values[name] ?? ""
    }

    /// Called only by the mpv executor. Native work happens outside the cache lock.
    func refresh(now: TimeInterval, force: Bool = false, read: (String) -> String) {
        guard force || now - lastRead >= 1 else { return }
        let next = Dictionary(uniqueKeysWithValues: Self.names.map { ($0, read($0)) })
        lock.lock()
        values = next
        lock.unlock()
        lastRead = now
    }

    /// Called on the same serial executor when switching files.
    func clear() {
        lock.lock()
        values.removeAll()
        lock.unlock()
        lastRead = -.infinity
    }
}
