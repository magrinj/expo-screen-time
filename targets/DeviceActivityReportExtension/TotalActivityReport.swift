import DeviceActivity
import SwiftUI
import ManagedSettings

/// Context for the total activity report
extension DeviceActivityReport.Context {
    static let totalActivity = Self("TotalActivity")
}

/// Data model for total activity
struct TotalActivityData {
    let totalDuration: TimeInterval
    let totalPickups: Int
    let appCount: Int
}

/// Report scene for total activity
struct TotalActivityReport: DeviceActivityReportScene {
    let context: DeviceActivityReport.Context = .totalActivity

    let content: (TotalActivityData) -> TotalActivityView

    func makeConfiguration(representing data: DeviceActivityResults<DeviceActivityData>) async -> TotalActivityData {
        var totalDuration: TimeInterval = 0
        var totalPickups = 0
        var appBundles = Set<String>()

        for await d in data {
            for await segment in d.activitySegments {
                totalDuration += segment.totalActivityDuration
                totalPickups += segment.totalPickupsWithoutApplicationActivity

                for await category in segment.categories {
                    for await app in category.applications {
                        if let bundle = app.application.bundleIdentifier {
                            appBundles.insert(bundle)
                        }
                    }
                }
            }
        }

        return TotalActivityData(
            totalDuration: totalDuration,
            totalPickups: totalPickups,
            appCount: appBundles.count
        )
    }
}

/// View for displaying total activity
struct TotalActivityView: View {
    let totalDuration: TimeInterval
    let totalPickups: Int
    let appCount: Int

    private let config = ReportConfiguration.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Total screen time
            VStack(alignment: .center, spacing: 4) {
                Text(formatDuration(totalDuration))
                    .font(.system(size: 48, weight: .bold))
                    .foregroundColor(config.textColor)

                Text("Screen Time")
                    .font(.subheadline)
                    .foregroundColor(config.secondaryTextColor)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 20)

            // Stats row
            HStack(spacing: 40) {
                Spacer()

                VStack(spacing: 4) {
                    Text("\(totalPickups)")
                        .font(.title2.bold())
                        .foregroundColor(config.accentColor)
                    Text("Pickups")
                        .font(.caption)
                        .foregroundColor(config.secondaryTextColor)
                }

                VStack(spacing: 4) {
                    Text("\(appCount)")
                        .font(.title2.bold())
                        .foregroundColor(config.accentColor)
                    Text("Apps Used")
                        .font(.caption)
                        .foregroundColor(config.secondaryTextColor)
                }

                Spacer()
            }
        }
        .padding()
        .background(config.backgroundColor)
    }

    private func formatDuration(_ duration: TimeInterval) -> String {
        let hours = Int(duration) / 3600
        let minutes = (Int(duration) % 3600) / 60

        if hours > 0 {
            return "\(hours)h \(minutes)m"
        } else if minutes > 0 {
            return "\(minutes)m"
        } else {
            return "0m"
        }
    }
}
