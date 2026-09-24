import ActivityKit
import SwiftUI
import WidgetKit

struct DownloadsLiveActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        let title: String
        let subtitle: String
        let status: String
        let progressPercent: Int
        let transferredText: String
        let queueSummaryText: String?
        /// Set while the app is backgrounded and cannot see progress; shown in place of it.
        let backgroundStatusText: String?
    }

    let sessionKey: String
}

@available(iOSApplicationExtension 16.1, *)
struct DownloadsLiveActivityWidget: Widget {
    private let downloadsUrl = URL(string: "nuvio://downloads")
    private let appBlue = Color(red: 30.0 / 255.0, green: 136.0 / 255.0, blue: 229.0 / 255.0)

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DownloadsLiveActivityAttributes.self) { context in
            DownloadActivityLockScreenView(context: context)
                .widgetURL(downloadsUrl)
        } dynamicIsland: { context in
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Text(statusLabel(context.state.status))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.88))
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if context.state.backgroundStatusText == nil {
                        Text(progressLabel(context.state.progressPercent, status: context.state.status))
                            .font(.title3.monospacedDigit().weight(.semibold))
                            .foregroundStyle(.white)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(context.state.title)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.86)
                            .truncationMode(.tail)
                        Text(context.state.subtitle)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.82))
                            .lineLimit(1)
                            .minimumScaleFactor(0.9)
                            .truncationMode(.tail)
                        if let summary = context.state.queueSummaryText, !summary.isEmpty {
                            Text(summary)
                                .font(.caption2.weight(.medium))
                                .foregroundStyle(appBlue)
                                .lineLimit(1)
                        }
                        if let background = context.state.backgroundStatusText {
                            Text(background)
                                .font(.caption)
                                .foregroundStyle(.white.opacity(0.86))
                                .lineLimit(1)
                        } else if context.state.progressPercent >= 0 {
                            ProgressView(value: normalizedProgress(context.state.progressPercent))
                                .progressViewStyle(.linear)
                                .tint(appBlue)
                        } else {
                            ProgressView()
                                .progressViewStyle(.linear)
                                .tint(appBlue)
                        }
                        HStack {
                            Text(context.state.backgroundStatusText == nil ? context.state.transferredText : "")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.white.opacity(0.86))
                            Spacer(minLength: 6)
                            Label(statusLabel(context.state.status), systemImage: "arrow.down")
                                .font(.caption)
                                .foregroundStyle(.white.opacity(0.86))
                        }
                    }
                    .padding(.top, 4)
                    .padding(.horizontal, 10)
                }
            } compactLeading: {
                AccentGlyphView()
            } compactTrailing: {
                if context.state.backgroundStatusText != nil {
                    Image(systemName: "arrow.down")
                        .font(.caption2.bold())
                        .foregroundStyle(appBlue)
                } else if context.state.progressPercent >= 0 {
                    Text(progressLabel(context.state.progressPercent, status: context.state.status))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(appBlue)
                } else {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(appBlue)
                        .scaleEffect(0.6)
                }
            } minimal: {
                AccentGlyphView()
            }
            .widgetURL(downloadsUrl)
            .keylineTint(appBlue)
        }
    }

    private func progressLabel(_ progressPercent: Int, status: String) -> String {
        if progressPercent < 0 {
            switch status.lowercased() {
            case "finding_sources": return "Finding…"
            case "preparing": return "Preparing…"
            case "waiting": return "Waiting"
            case "retrying": return "Retrying"
            case "paused": return "Paused"
            case "failed": return "Failed"
            default: return "Starting…"
            }
        }
        return "\(max(0, min(100, progressPercent)))%"
    }

    private func normalizedProgress(_ progressPercent: Int) -> Double {
        guard progressPercent >= 0 else { return 0 }
        return min(max(Double(progressPercent) / 100.0, 0), 1)
    }

    private func statusLabel(_ status: String) -> String {
        switch status.lowercased() {
        case "finding_sources": return "Finding sources"
        case "downloading": return "Downloading"
        case "starting": return "Starting"
        case "waiting": return "Waiting"
        case "retrying": return "Retrying"
        case "paused": return "Paused"
        case "failed": return "Failed"
        case "preparing": return "Preparing"
        default: return "Active"
        }
    }
}

@available(iOSApplicationExtension 16.1, *)
private struct DownloadActivityLockScreenView: View {
    let context: ActivityViewContext<DownloadsLiveActivityAttributes>

    var body: some View {
        ZStack {
            LinearGradient(
                colors: backgroundGradientColors,
                startPoint: .topLeading,
                endPoint: .bottomTrailing,
            )

            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 10) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(context.state.title)
                            .font(.headline.weight(.semibold))
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(context.state.subtitle)
                            .font(.subheadline)
                            .foregroundStyle(.white.opacity(0.82))
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                        if let summary = context.state.queueSummaryText, !summary.isEmpty {
                            Text(summary)
                                .font(.caption.weight(.medium))
                                .foregroundStyle(Color(red: 144.0 / 255.0, green: 202.0 / 255.0, blue: 249.0 / 255.0))
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 10)
                    if context.state.backgroundStatusText == nil {
                        Text(progressLabel(context.state.progressPercent))
                            .font(.title3.monospacedDigit().weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.top, 1)
                    }
                }

                // While the app is backgrounded it cannot see progress, so it shows none
                // rather than a frozen figure that looks live.
                if let background = context.state.backgroundStatusText {
                    Label(background, systemImage: "arrow.down")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.86))
                } else {
                    ProgressView(value: normalizedProgress(context.state.progressPercent))
                        .progressViewStyle(.linear)
                        .tint(.white)

                    HStack {
                        Label(statusLabel(context.state.status), systemImage: "arrow.down")
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.86))
                        Spacer()
                        Text(context.state.transferredText)
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.white.opacity(0.86))
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 16)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .frame(maxWidth: .infinity, minHeight: 168, maxHeight: .infinity, alignment: .top)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.white.opacity(0.10), lineWidth: 1),
        )
        .activityBackgroundTint(.clear)
        .activitySystemActionForegroundColor(.white)
    }

    private func progressLabel(_ progressPercent: Int) -> String {
        if progressPercent < 0 { return "--%" }
        return "\(max(0, min(100, progressPercent)))%"
    }

    private func normalizedProgress(_ progressPercent: Int) -> Double {
        guard progressPercent >= 0 else { return 0 }
        return min(max(Double(progressPercent) / 100.0, 0), 1)
    }

    private func statusLabel(_ status: String) -> String {
        switch status.lowercased() {
        case "downloading": return "Downloading"
        case "paused": return "Paused"
        case "failed": return "Failed"
        default: return "Active"
        }
    }

    private var backgroundGradientColors: [Color] {
        let accent = Color(red: 30.0 / 255.0, green: 136.0 / 255.0, blue: 229.0 / 255.0)
        return [
            accent.opacity(0.92),
            accent.opacity(0.42),
            Color.black.opacity(0.97),
        ]
    }
}

private struct AccentGlyphView: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(Color(red: 30.0 / 255.0, green: 136.0 / 255.0, blue: 229.0 / 255.0).opacity(0.88))
            Image(systemName: "arrow.down")
                .font(.caption2.bold())
                .foregroundStyle(.white)
        }
        .frame(width: 22, height: 22)
    }
}
