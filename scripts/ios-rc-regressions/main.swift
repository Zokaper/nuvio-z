import Foundation

func require(_ value: Bool, _ message: String) {
    if !value { fatalError(message) }
}

// Use the production observer. With queue: .main, posting waits for main while holding the
// store lock. Composition acquiring that lock completes the device's observed deadlock cycle.
DownloadsLiveActivityManager.shared.start()
let storeLock = NSLock()
let posted = DispatchSemaphore(value: 0)
DispatchQueue.global().async {
    storeLock.lock()
    NotificationCenter.default.post(name: Notification.Name("NuvioDownloadsLiveStatusUpdated"), object: nil)
    storeLock.unlock()
    posted.signal()
}
require(posted.wait(timeout: .now() + 2) == .success, "Live Activity publisher waited for main")
require(storeLock.try(), "Composition cannot acquire DownloadStore.lock")
storeLock.unlock()
print("PASS Live Activity posting never waits for main under the store lock")

// Model a synchronous remote sub-add blocked in libmpv, then exit while it is in flight.
for cycle in 0..<50 {
    let executor = MPVSerialExecutor()
    let entered = DispatchSemaphore(value: 0)
    let unblock = DispatchSemaphore(value: 0)
    let destroyed = DispatchSemaphore(value: 0)
    let freshFinished = DispatchSemaphore(value: 0)
    var events: [String] = [] // Accessed only on this executor; read after the destroyed signal.
    executor.perform {
        require(!Thread.isMainThread, "Synchronous mpv call reached main")
        events.append("subtitle-enter")
        entered.signal()
        require(unblock.wait(timeout: .now() + 2) == .success, "Test did not unblock subtitle")
        events.append("subtitle-return")
    }
    require(entered.wait(timeout: .now() + 2) == .success, "Subtitle did not start")
    executor.perform { events.append("stale-command") }
    let began = Date()
    require(executor.close {
        require(!Thread.isMainThread, "mpv_terminate_destroy reached main")
        events.append("destroy")
        destroyed.signal()
    }, "First close failed")
    require(Date().timeIntervalSince(began) < 0.5, "Exit waited for an in-flight mpv call")
    require(!executor.isOpen, "Late snapshot publication allowed after exit")
    require(!executor.close { fatalError("Double destruction") }, "Close was not idempotent")
    executor.perform { fatalError("Post-exit command accessed old context") }
    executor.performAfter(0.001) { fatalError("Delayed subtitle accessed destroyed context") }
    require(destroyed.wait(timeout: .now() + 0.001) == .timedOut, "Destroy raced subtitle call")
    // A recreated player has its own queue; an old blocked subtitle cannot block the new player.
    let fresh = MPVSerialExecutor()
    fresh.perform { freshFinished.signal() }
    require(freshFinished.wait(timeout: .now() + 1) == .success, "Recreation shares old queue")
    fresh.close {}
    unblock.signal()
    require(destroyed.wait(timeout: .now() + 2) == .success, "Destruction never completed")
    require(events == ["subtitle-enter", "subtitle-return", "destroy"], "Unsafe lifecycle order in cycle \(cycle): \(events)")
}
// Drain main publications and delayed work before ending the harness.
RunLoop.main.run(until: Date().addingTimeInterval(0.05))
print("PASS 50 subtitle / exit / recreation cycles, ordered destruction, stale-work rejection")
