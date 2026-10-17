import Foundation
#if NUVIO_SELF_SIGNING
import AppIntents
import BackgroundTasks
import ComposeApp

/// Phase 2 (PLAN-nuvio-z-self-signing.md): the ways Nuvio Z renews itself without being opened. Compiled only into
/// builds linked with the self-signing library (build-ios-ipa.sh sets NUVIO_SELF_SIGNING together with
/// NUVIO_IOS_SELF_SIGNING), so the Shortcuts action never appears in a normal build. The renewal itself is Kotlin
/// (`selfSigningRefresh`), the same path the app uses when it opens.
enum SelfSigningBackground {
    /// Also listed in Info.plist's BGTaskSchedulerPermittedIdentifiers by build-ios-ipa.sh.
    static let refreshTask = "com.nuvio.app.selfsigning.refresh"

    /// Before the app finishes launching, as BGTaskScheduler requires.
    static func register() {
        UserDefaults.standard.set(true, forKey: "nzs.intent.compiled")
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: refreshTask, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { return task.setTaskCompleted(success: false) }
            handle(task)
        }
        if !registered {
            UserDefaults.standard.set("not permitted (Info.plist)", forKey: "nzs.bgtask.error")
            return
        }
        schedule()
    }

    /// Opportunistic: iOS decides when (if ever) it runs. The Shortcuts automation is the dependable path.
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: refreshTask)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 12 * 3600)
        do {
            try BGTaskScheduler.shared.submit(request)
            UserDefaults.standard.set(ISO8601DateFormatter().string(from: Date()), forKey: "nzs.bgtask.submittedAt")
            UserDefaults.standard.removeObject(forKey: "nzs.bgtask.error")
        } catch {
            UserDefaults.standard.set("\(error)", forKey: "nzs.bgtask.error")
        }
    }

    private static func handle(_ task: BGAppRefreshTask) {
        schedule()
        var finished = false
        let finish: (Bool) -> Void = { ok in
            DispatchQueue.main.async {
                guard !finished else { return }
                finished = true
                task.setTaskCompleted(success: ok)
            }
        }
        task.expirationHandler = { finish(false) }
        SelfSigningSpike_iosKt.selfSigningRefresh(trigger: "background") { _ in
            finish(SelfSigningSpike_iosKt.selfSigningLastRefreshOk())
        }
    }
}

/// "Refresh Nuvio Z" in Shortcuts. Runs in the background without opening the app; LocalDevVPN has to be connected
/// (the daily shortcut connects it first with Set VPN).
@available(iOS 16.0, *)
struct RefreshNuvioZIntent: AppIntent {
    static var title: LocalizedStringResource = "Refresh Nuvio Z"
    static var description = IntentDescription("Renews Nuvio Z’s 7-day signature so it keeps opening. Connect LocalDevVPN first.")
    static var openAppWhenRun: Bool = false

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let line: String = await withCheckedContinuation { continuation in
            SelfSigningSpike_iosKt.selfSigningRefresh(trigger: "shortcut") { continuation.resume(returning: $0) }
        }
        return .result(dialog: "\(line)")
    }
}

/// Puts the action in the Shortcuts app with no setup.
@available(iOS 16.0, *)
struct NuvioZShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: RefreshNuvioZIntent(), phrases: ["Refresh \(.applicationName)"])
    }
}
#else
enum SelfSigningBackground {
    static func register() {}
}
#endif
