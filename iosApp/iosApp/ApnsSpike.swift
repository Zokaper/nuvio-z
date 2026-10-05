#if DEBUG
import UIKit
import UserNotifications

/// Throwaway spike: can a SideStore-signed install obtain an APNs device token?
///
/// Writes `Documents/nuvio_diagnostics/apns-spike.txt` (visible in Files > On My iPhone > Nuvio Z Debug).
/// Records what SideStore actually put in the installed app (bundle id, embedded profile, entitlements)
/// and the outcome of `registerForRemoteNotifications()`. Delete once the verdict is recorded.
enum ApnsSpike {
    static func run() {
        record("===== launch =====")
        record("bundleIdentifier=\(Bundle.main.bundleIdentifier ?? "nil")")
        recordProfile()
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            record("notification authorization granted=\(granted) error=\(error.map { "\($0)" } ?? "none")")
            DispatchQueue.main.async {
                UIApplication.shared.registerForRemoteNotifications()
                record("registerForRemoteNotifications() called; waiting for callback")
            }
        }
    }

    static func didRegister(_ token: Data) {
        record("RESULT=SUCCESS apnsToken=\(token.map { String(format: "%02x", $0) }.joined())")
    }

    static func didFail(_ error: Error) {
        let ns = error as NSError
        record("RESULT=FAILURE domain=\(ns.domain) code=\(ns.code) description=\(ns.localizedDescription)")
    }

    private static func recordProfile() {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url) else {
            record("embedded.mobileprovision: ABSENT")
            return
        }
        guard let start = data.range(of: Data("<?xml".utf8)),
              let end = data.range(of: Data("</plist>".utf8)),
              let plist = try? PropertyListSerialization.propertyList(
                from: data.subdata(in: start.lowerBound..<end.upperBound), format: nil
              ) as? [String: Any] else {
            record("embedded.mobileprovision: present but unparseable (\(data.count) bytes)")
            return
        }
        let entitlements = plist["Entitlements"] as? [String: Any] ?? [:]
        record("profile name=\(plist["Name"] ?? "?") team=\(plist["TeamIdentifier"] ?? "?") expires=\(plist["ExpirationDate"] ?? "?")")
        record("profile entitlement keys=\(entitlements.keys.sorted())")
        record("profile aps-environment=\(entitlements["aps-environment"].map { "\($0)" } ?? "ABSENT")")
    }

    private static func record(_ line: String) {
        NSLog("[ApnsSpike] %@", line)
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let directory = documents.appendingPathComponent("nuvio_diagnostics", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("apns-spike.txt")
        let formatter = ISO8601DateFormatter()
        let data = Data("\(formatter.string(from: Date())) \(line)\n".utf8)
        if let handle = try? FileHandle(forWritingTo: file) {
            handle.seekToEndOfFile()
            handle.write(data)
            try? handle.close()
        } else {
            try? data.write(to: file)
        }
    }
}
#endif
