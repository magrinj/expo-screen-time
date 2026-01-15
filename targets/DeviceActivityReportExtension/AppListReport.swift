import DeviceActivity
import SwiftUI
import ManagedSettings

/// Context for the app list report
extension DeviceActivityReport.Context {
    static let appList = Self("AppList")
}

/// Data model for app list
struct AppListData {
    let apps: [AppRowData]
}

struct AppRowData: Identifiable {
    let id: String  // bundleIdentifier
    let token: ApplicationToken?
    let name: String
    let duration: TimeInterval
    let pickups: Int
    let category: String
}

/// Report scene for app list
struct AppListReport: DeviceActivityReportScene {
    let context: DeviceActivityReport.Context = .appList

    let content: (AppListData) -> AppListView

    func makeConfiguration(representing data: DeviceActivityResults<DeviceActivityData>) async -> AppListData {
        var apps: [AppRowData] = []
        var seen = Set<String>()

        for await d in data {
            for await segment in d.activitySegments {
                for await category in segment.categories {
                    let categoryName = category.category.localizedDisplayName ?? "Other"

                    for await app in category.applications {
                        let name = app.application.localizedDisplayName ?? "Unknown"
                        let bundle = app.application.bundleIdentifier ?? UUID().uuidString

                        // Skip unnamed apps or duplicates
                        guard name != bundle, !seen.contains(bundle) else { continue }
                        seen.insert(bundle)

                        // Skip apps with zero duration
                        guard app.totalActivityDuration > 0 else { continue }

                        apps.append(AppRowData(
                            id: bundle,
                            token: app.application.token,
                            name: name,
                            duration: app.totalActivityDuration,
                            pickups: app.numberOfPickups,
                            category: categoryName
                        ))
                    }
                }
            }
        }

        // Sort by duration descending
        apps.sort { $0.duration > $1.duration }

        return AppListData(apps: apps)
    }
}

/// View for displaying app list
struct AppListView: View {
    let apps: [AppRowData]

    private let config = ReportConfiguration.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if apps.isEmpty {
                emptyState
            } else {
                ForEach(apps) { app in
                    AppRowView(app: app)

                    if app.id != apps.last?.id {
                        Divider()
                            .padding(.leading, 56)
                    }
                }
            }
        }
        .background(config.backgroundColor)
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "apps.iphone")
                .font(.system(size: 40))
                .foregroundColor(config.secondaryTextColor)

            Text("No app usage data")
                .font(.headline)
                .foregroundColor(config.textColor)

            Text("Usage data will appear after using apps")
                .font(.subheadline)
                .foregroundColor(config.secondaryTextColor)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }
}

struct AppRowView: View {
    let app: AppRowData

    private let config = ReportConfiguration.shared

    var body: some View {
        HStack(spacing: 12) {
            // App icon placeholder
            // Note: Label(token) requires ManagedSettingsUI which isn't available in report extensions
            RoundedRectangle(cornerRadius: 8)
                .fill(config.accentColor.opacity(0.2))
                .frame(width: 40, height: 40)
                .overlay(
                    Image(systemName: "app.fill")
                        .foregroundColor(config.accentColor)
                )

            // App name
            VStack(alignment: .leading, spacing: 2) {
                Text(app.name)
                    .font(.body)
                    .foregroundColor(config.textColor)
                    .lineLimit(1)

                Text(app.category)
                    .font(.caption)
                    .foregroundColor(config.secondaryTextColor)
            }

            Spacer()

            // Duration
            VStack(alignment: .trailing, spacing: 2) {
                Text(formatDuration(app.duration))
                    .font(.body.monospacedDigit())
                    .foregroundColor(config.accentColor)

                if config.showPickups && app.pickups > 0 {
                    Text("\(app.pickups) pickups")
                        .font(.caption)
                        .foregroundColor(config.secondaryTextColor)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func formatDuration(_ duration: TimeInterval) -> String {
        let hours = Int(duration) / 3600
        let minutes = (Int(duration) % 3600) / 60
        let seconds = Int(duration) % 60

        if hours > 0 {
            return String(format: "%dh %dm", hours, minutes)
        } else if minutes > 0 {
            return String(format: "%dm %ds", minutes, seconds)
        } else {
            return String(format: "%ds", seconds)
        }
    }
}
