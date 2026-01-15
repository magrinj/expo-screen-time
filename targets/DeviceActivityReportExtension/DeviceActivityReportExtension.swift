import DeviceActivity
import SwiftUI

@main
struct DeviceActivityReportExtensionApp: DeviceActivityReportExtension {
    var body: some DeviceActivityReportScene {
        // Total activity report - shows total screen time
        TotalActivityReport { totalActivity in
            TotalActivityView(
                totalDuration: totalActivity.totalDuration,
                totalPickups: totalActivity.totalPickups,
                appCount: totalActivity.appCount
            )
        }

        // App list report - shows per-app breakdown
        AppListReport { appList in
            AppListView(apps: appList.apps)
        }

        // Hourly chart report - shows usage by hour
        HourlyChartReport { hourlyData in
            HourlyChartView(
                totalDuration: hourlyData.totalDuration,
                segments: hourlyData.segments
            )
        }
    }
}
