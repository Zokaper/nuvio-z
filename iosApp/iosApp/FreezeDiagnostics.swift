#if DEBUG
import MetricKit
import UIKit
import UIKit.UIGestureRecognizerSubclass

/// Debug-build-only evidence for the Phase 8 input freeze (`Nuvio Z Debug`, never release).
///
/// The freeze kills every touch, the native tab bar included, and a trip out of the app
/// clears it. That fits two different faults - a stalled main thread, or an invisible
/// window-level view that takes the touches - and they need different fixes, so this
/// records what tells them apart and nothing else:
///
/// - a main-thread watchdog: a stall over 1.5 s is logged with its length;
/// - the class chain of the view each touch lands on, since a live main thread with
///   touches landing somewhere unexpected is the overlay case;
/// - on every trip to the background, the window stack and any view covering most of a
///   window, plus the last touches and notes;
/// - MetricKit hang reports, which carry real main-thread call stacks.
///
/// Everything goes to `Documents/nuvio_diagnostics/`, which the debug build exposes in
/// the Files app ("On My iPhone > Nuvio Z Debug").
final class FreezeDiagnostics: NSObject, UIGestureRecognizerDelegate, MXMetricManagerSubscriber {
    static let shared = FreezeDiagnostics()

    private let lock = NSLock()
    private let watchdogQueue = DispatchQueue(label: "com.nuvio.diagnostics.watchdog")
    private var watchdog: DispatchSourceTimer?
    private var pingSentAt: Date?
    private var stallReported = false
    private var recentTouches: [String] = []
    private var notes: [String] = []
    private var fileHandle: FileHandle?
    private var probedWindows = Set<ObjectIdentifier>()
    /// Suspension stops the main thread too; a stall only counts while the app is active.
    private var isActive = false

    private let stallThreshold: TimeInterval = 1.5
    private let pingInterval: TimeInterval = 0.25

    func start() {
        openLogFile()
        log("launch \(Bundle.main.bundleIdentifier ?? "?")")
        MXMetricManager.shared.add(self)
        startWatchdog()
        let center = NotificationCenter.default
        center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.dumpState(reason: "background")
        }
        center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.setActive(false)
        }
        center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.setActive(true)
            self?.log("active")
            self?.installTouchProbes()
        }
    }

    private func setActive(_ active: Bool) {
        lock.lock()
        isActive = active
        pingSentAt = nil
        stallReported = false
        lock.unlock()
    }

    /// A state change worth seeing next to a freeze (gate readiness, main content mount).
    func note(_ text: String) {
        lock.lock()
        notes.append("\(timestamp()) \(text)")
        if notes.count > 40 { notes.removeFirst(notes.count - 40) }
        lock.unlock()
    }

    // MARK: Watchdog

    private func startWatchdog() {
        let timer = DispatchSource.makeTimerSource(queue: watchdogQueue)
        timer.schedule(deadline: .now() + pingInterval, repeating: pingInterval)
        timer.setEventHandler { [weak self] in self?.tick() }
        timer.resume()
        watchdog = timer
    }

    private func tick() {
        lock.lock()
        let now = Date()
        guard let sentAt = pingSentAt else {
            pingSentAt = now
            lock.unlock()
            DispatchQueue.main.async { [weak self] in self?.pong(sentAt: now) }
            return
        }
        let waited = now.timeIntervalSince(sentAt)
        let report = isActive && waited > stallThreshold && !stallReported
        if report { stallReported = true }
        lock.unlock()
        if report {
            log("STALL main thread blocked > \(String(format: "%.1f", stallThreshold))s")
        }
    }

    private func pong(sentAt: Date) {
        lock.lock()
        let wasStalled = stallReported
        stallReported = false
        pingSentAt = nil
        lock.unlock()
        if wasStalled {
            log("STALL ended after \(String(format: "%.2f", Date().timeIntervalSince(sentAt)))s")
        }
    }

    // MARK: Touches

    private func installTouchProbes() {
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows where !probedWindows.contains(ObjectIdentifier(window)) {
                probedWindows.insert(ObjectIdentifier(window))
                let probe = TouchProbe(target: nil, action: nil)
                probe.onTouch = { [weak self, weak window] location in
                    guard let self, let window else { return }
                    self.recordTouch(in: window, at: location)
                }
                probe.cancelsTouchesInView = false
                probe.delaysTouchesBegan = false
                probe.delaysTouchesEnded = false
                probe.delegate = self
                window.addGestureRecognizer(probe)
            }
        }
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool { true }

    private func recordTouch(in window: UIWindow, at location: CGPoint) {
        var chain: [String] = []
        var view = window.hitTest(location, with: nil)
        while let current = view, chain.count < 8 {
            chain.append(String(describing: type(of: current)))
            view = current.superview
        }
        let entry = "\(timestamp()) touch (\(Int(location.x)),\(Int(location.y))) window=\(type(of: window)) hit=\(chain.joined(separator: " < "))"
        lock.lock()
        recentTouches.append(entry)
        if recentTouches.count > 30 { recentTouches.removeFirst(recentTouches.count - 30) }
        lock.unlock()
    }

    // MARK: State dump

    private func dumpState(reason: String) {
        var lines = ["--- state (\(reason))"]
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows {
                lines.append("window \(type(of: window)) level=\(window.windowLevel.rawValue) key=\(window.isKeyWindow) hidden=\(window.isHidden) interaction=\(window.isUserInteractionEnabled)")
                collectCoveringViews(in: window, window: window, depth: 0, into: &lines)
            }
        }
        lock.lock()
        lines.append("notes:")
        lines.append(contentsOf: notes)
        lines.append("touches:")
        lines.append(contentsOf: recentTouches)
        lock.unlock()
        lines.append("--- end")
        log(lines.joined(separator: "\n"))
    }

    /// Views covering most of their window: the only kind that can swallow every touch.
    private func collectCoveringViews(in view: UIView, window: UIWindow, depth: Int, into lines: inout [String]) {
        guard depth < 24, lines.count < 300 else { return }
        let windowArea = window.bounds.width * window.bounds.height
        for subview in view.subviews {
            let frame = subview.convert(subview.bounds, to: window)
            let area = frame.intersection(window.bounds).width * frame.intersection(window.bounds).height
            if windowArea > 0, area / windowArea >= 0.8 {
                lines.append("\(String(repeating: " ", count: depth))\(type(of: subview)) alpha=\(subview.alpha) hidden=\(subview.isHidden) interaction=\(subview.isUserInteractionEnabled) opaque=\(subview.isOpaque)")
            }
            collectCoveringViews(in: subview, window: window, depth: depth + 1, into: &lines)
        }
    }

    // MARK: MetricKit

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for payload in payloads {
            let hangs = payload.hangDiagnostics?.count ?? 0
            log("metrickit diagnostic payload: hangs=\(hangs)")
            write(payload.jsonRepresentation(), named: "metrickit-\(fileStamp()).json")
        }
    }

    // MARK: Files

    private func directory() -> URL? {
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
        let url = documents.appendingPathComponent("nuvio_diagnostics", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func openLogFile() {
        guard let directory = directory() else { return }
        let existing = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        existing
            .filter { $0.lastPathComponent.hasPrefix("session-") }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
            .dropLast(4)
            .forEach { try? FileManager.default.removeItem(at: $0) }
        let url = directory.appendingPathComponent("session-\(fileStamp()).log")
        FileManager.default.createFile(atPath: url.path, contents: nil)
        fileHandle = try? FileHandle(forWritingTo: url)
    }

    private func write(_ data: Data, named name: String) {
        guard let url = directory()?.appendingPathComponent(name) else { return }
        try? data.write(to: url)
    }

    private func log(_ line: String) {
        let data = Data("\(timestamp()) \(line)\n".utf8)
        lock.lock()
        fileHandle?.seekToEndOfFile()
        fileHandle?.write(data)
        lock.unlock()
    }

    private func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter.string(from: Date())
    }

    private func fileStamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return formatter.string(from: Date())
    }
}

/// Watches touches without ever taking part: it fails on the first one it sees.
private final class TouchProbe: UIGestureRecognizer {
    var onTouch: ((CGPoint) -> Void)?

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        if let touch = touches.first, let view = view {
            onTouch?(touch.location(in: view))
        }
        state = .failed
    }
}
#endif
