import DeviceActivity
import SwiftUI

/// Context for the hourly chart report
extension DeviceActivityReport.Context {
    static let hourlyChart = Self("HourlyChart")
}

/// Data model for hourly chart
struct HourlyChartData {
    let totalDuration: TimeInterval
    let segments: [HourSegment]
}

struct HourSegment: Identifiable {
    let id: Int
    let hour: Int
    let duration: TimeInterval
}

/// Report scene for hourly chart
struct HourlyChartReport: DeviceActivityReportScene {
    let context: DeviceActivityReport.Context = .hourlyChart

    let content: (HourlyChartData) -> HourlyChartView

    func makeConfiguration(representing data: DeviceActivityResults<DeviceActivityData>) async -> HourlyChartData {
        // Initialize 24 hour buckets
        var hourlyDurations: [Int: TimeInterval] = [:]
        for hour in 0..<24 {
            hourlyDurations[hour] = 0
        }

        var totalDuration: TimeInterval = 0

        // Iterate segments — each segment represents a time window
        // When using .hourly filter, each segment is one hour
        // When using .daily filter, we aggregate by hour from the segment's date interval
        for await d in data {
            for await segment in d.activitySegments {
                let startHour = Calendar.current.component(.hour, from: segment.dateInterval.start)
                let duration = segment.totalActivityDuration

                hourlyDurations[startHour, default: 0] += duration
                totalDuration += duration
            }
        }

        let segments = (0..<24).map { hour in
            HourSegment(id: hour, hour: hour, duration: hourlyDurations[hour] ?? 0)
        }

        return HourlyChartData(totalDuration: totalDuration, segments: segments)
    }
}

/// View for displaying hourly chart
struct HourlyChartView: View {
    let totalDuration: TimeInterval
    let segments: [HourSegment]

    private let config = ReportConfiguration.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Header
            HStack {
                Text("Activity by Hour")
                    .font(.headline)
                    .foregroundColor(config.textColor)

                Spacer()

                Text(formatDuration(totalDuration))
                    .font(.subheadline)
                    .foregroundColor(config.secondaryTextColor)
            }

            // Chart
            GeometryReader { geometry in
                HStack(alignment: .bottom, spacing: 2) {
                    ForEach(segments) { segment in
                        BarView(
                            segment: segment,
                            maxDuration: maxDuration,
                            height: geometry.size.height - 30
                        )
                    }
                }
            }
            .frame(height: 150)

            // Hour labels
            HStack {
                Text("12am")
                    .font(.caption2)
                    .foregroundColor(config.secondaryTextColor)
                Spacer()
                Text("6am")
                    .font(.caption2)
                    .foregroundColor(config.secondaryTextColor)
                Spacer()
                Text("12pm")
                    .font(.caption2)
                    .foregroundColor(config.secondaryTextColor)
                Spacer()
                Text("6pm")
                    .font(.caption2)
                    .foregroundColor(config.secondaryTextColor)
                Spacer()
                Text("12am")
                    .font(.caption2)
                    .foregroundColor(config.secondaryTextColor)
            }
        }
        .padding()
        .background(config.backgroundColor)
    }

    private var maxDuration: TimeInterval {
        segments.map(\.duration).max() ?? 1
    }

    private func formatDuration(_ duration: TimeInterval) -> String {
        let hours = Int(duration) / 3600
        let minutes = (Int(duration) % 3600) / 60

        if hours > 0 {
            return "\(hours)h \(minutes)m"
        } else {
            return "\(minutes)m"
        }
    }
}

struct BarView: View {
    let segment: HourSegment
    let maxDuration: TimeInterval
    let height: CGFloat

    private let config = ReportConfiguration.shared

    var body: some View {
        VStack(spacing: 0) {
            Spacer()

            RoundedRectangle(cornerRadius: 2)
                .fill(barColor)
                .frame(height: barHeight)
        }
    }

    private var barHeight: CGFloat {
        guard maxDuration > 0 else { return 4 }
        let ratio = segment.duration / maxDuration
        return max(4, height * CGFloat(ratio))
    }

    private var barColor: Color {
        if segment.duration > 0 {
            return config.accentColor
        } else {
            return config.secondaryTextColor.opacity(0.2)
        }
    }
}
