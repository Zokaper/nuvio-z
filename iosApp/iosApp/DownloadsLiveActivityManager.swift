import Foundation
#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
import ActivityKit
#endif

private let downloadsLiveStatusUpdatedNotification = Notification.Name("NuvioDownloadsLiveStatusUpdated")
private let downloadsLiveStatusPayloadKey = "nuvio.downloads.live_status.payload"

final class DownloadsLiveActivityManager {
    static let shared = DownloadsLiveActivityManager()

    private var observer: NSObjectProtocol?
    // Main-thread only. Payload notifications used to start one unawaited Task each, so a
    // burst of progress ran ActivityKit updates concurrently and out of order, and two of
    // them could both find no activity and both request one.
    private var isApplying = false
    private var needsReapply = false

    private init() {}

    func start() {
        guard observer == nil else { return }

        observer = NotificationCenter.default.addObserver(
            forName: downloadsLiveStatusUpdatedNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.syncFromPayloadStore()
        }

        syncFromPayloadStore()
    }

    private func syncFromPayloadStore() {
#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
        guard #available(iOS 16.1, *) else { return }

        if isApplying {
            needsReapply = true
            return
        }
        isApplying = true
        Task { @MainActor in
            repeat {
                needsReapply = false
                await apply(loadPayload())
            } while needsReapply
            isApplying = false
        }
#endif
    }

    private func loadPayload() -> DownloadsLiveStatusPayload? {
        guard let encoded = UserDefaults.standard.string(forKey: downloadsLiveStatusPayloadKey) else {
            return nil
        }
        let data = Data(encoded.utf8)
        return try? JSONDecoder().decode(DownloadsLiveStatusPayload.self, from: data)
    }

#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
    @available(iOS 16.1, *)
    private func apply(_ payload: DownloadsLiveStatusPayload?) async {
        let allActivities = Activity<DownloadsLiveActivityAttributes>.activities

        guard let payload else {
            for activity in allActivities {
                await activity.end(dismissalPolicy: .immediate)
            }
            return
        }

        let state = DownloadsLiveActivityAttributes.ContentState(
            title: payload.title,
            subtitle: payload.subtitle,
            status: payload.status,
            progressPercent: payload.progressPercent,
            transferredText: transferredText(payload),
            queueSummaryText: payload.queueSummaryText
        )

        // Stable session identity: update existing activity if present, otherwise request one
        if let existing = allActivities.first {
            await existing.update(using: state)
            for orphan in allActivities where orphan.id != existing.id {
                await orphan.end(dismissalPolicy: .immediate)
            }
        } else {
            let attributes = DownloadsLiveActivityAttributes(
                sessionKey: "nuvio.downloads.session"
            )

            _ = try? Activity<DownloadsLiveActivityAttributes>.request(
                attributes: attributes,
                contentState: state,
                pushType: nil
            )
        }
    }
#endif

    private func transferredText(_ payload: DownloadsLiveStatusPayload) -> String {
        if payload.totalBytes == nil && payload.downloadedBytes == 0 {
            switch payload.status.lowercased() {
            case "finding_sources": return "Finding sources"
            case "preparing": return "Preparing"
            case "waiting": return "Waiting"
            case "starting": return "Starting"
            case "retrying": return "Retrying"
            case "paused": return "Paused"
            case "failed": return "Failed"
            default: break
            }
        }
        let downloaded = formatBytes(payload.downloadedBytes)
        if let total = payload.totalBytes {
            return "\(downloaded) / \(formatBytes(total))"
        }
        return downloaded
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}

#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
@available(iOS 16.1, *)
struct DownloadsLiveActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        let title: String
        let subtitle: String
        let status: String
        let progressPercent: Int
        let transferredText: String
        let queueSummaryText: String?
    }

    let sessionKey: String
}
#endif

private struct DownloadsLiveStatusPayload: Decodable {
    let id: String
    let title: String
    let subtitle: String
    let status: String
    let downloadedBytes: Int64
    let totalBytes: Int64?
    let progressPercent: Int
    let activeCount: Int?
    let remainingCount: Int?
    let queueSummaryText: String?
}
