import ExpoModulesCore
import SwiftUI
import UIKit

// Conditionally import DeviceActivity for iOS 16+
#if canImport(DeviceActivity)
import DeviceActivity
#endif

// MARK: - Observable Model

class ReportViewModel: ObservableObject {
    @Published var contextName: String = "TotalActivity"
    @Published var filterSegment: String = "daily"
    @Published var filterDate: Date = Date()
}

// MARK: - SwiftUI Container

@available(iOS 16.0, *)
struct ReportContainerView: View {
    @ObservedObject var model: ReportViewModel

    var body: some View {
        let context = DeviceActivityReport.Context(rawValue: model.contextName)
        let filter = Self.createFilter(
            contextName: model.contextName,
            segment: model.filterSegment,
            date: model.filterDate
        )

        DeviceActivityReport(context, filter: filter)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    static func createFilter(contextName: String, segment: String, date: Date) -> DeviceActivityFilter {
        let calendar = Calendar.current

        // Determine the date interval based on segment
        let interval: DateInterval
        switch segment {
        case "daily":
            let startOfDay = calendar.startOfDay(for: date)
            let endOfDay = calendar.date(byAdding: .day, value: 1, to: startOfDay) ?? date
            interval = DateInterval(start: startOfDay, end: endOfDay)
        case "weekly":
            let startOfWeek = calendar.date(from: calendar.dateComponents([.yearForWeekOfYear, .weekOfYear], from: date)) ?? date
            let endOfWeek = calendar.date(byAdding: .weekOfYear, value: 1, to: startOfWeek) ?? date
            interval = DateInterval(start: startOfWeek, end: endOfWeek)
        default:
            let startOfDay = calendar.startOfDay(for: Date())
            let endOfDay = calendar.date(byAdding: .day, value: 1, to: startOfDay) ?? Date()
            interval = DateInterval(start: startOfDay, end: endOfDay)
        }

        // HourlyChart needs hourly segments, others use daily segments
        if contextName == "HourlyChart" {
            return DeviceActivityFilter(segment: .hourly(during: interval))
        } else {
            return DeviceActivityFilter(segment: .daily(during: interval))
        }
    }
}

// MARK: - ExpoView

/// View for displaying DeviceActivityReport.
/// Works on all iOS versions but only shows actual report on iOS 16+.
/// Scroll passthrough is handled on the RN side via pointerEvents="none".
class ExpoScreenTimeReportView: ExpoView {
    private var hostingController: UIHostingController<AnyView>?
    private let viewModel = ReportViewModel()

    required init(appContext: AppContext? = nil) {
        super.init(appContext: appContext)
        buildHostingController()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        hostingController?.view.frame = bounds
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if let parentVC = self.reactViewController(), let hosting = hostingController {
            if hosting.parent == nil {
                parentVC.addChild(hosting)
                hosting.didMove(toParent: parentVC)
            }
        }
    }

    // MARK: - Props

    func setContext(_ context: String) {
        viewModel.contextName = context
    }

    func setFilterSegment(_ segment: String) {
        viewModel.filterSegment = segment
    }

    func setFilterDate(_ timestamp: Double) {
        viewModel.filterDate = Date(timeIntervalSince1970: timestamp / 1000)
    }

    // MARK: - Private

    private func buildHostingController() {
        let wrappedView: AnyView

        if #available(iOS 16.0, *) {
            wrappedView = AnyView(ReportContainerView(model: viewModel))
        } else {
            wrappedView = AnyView(
                Text("DeviceActivityReport requires iOS 16+")
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            )
        }

        let hosting = UIHostingController(rootView: wrappedView)
        hosting.view.backgroundColor = .clear
        hostingController = hosting

        addSubview(hosting.view)
        hosting.view.frame = bounds

        if let parentVC = self.reactViewController() {
            parentVC.addChild(hosting)
            hosting.didMove(toParent: parentVC)
        }
    }
}
