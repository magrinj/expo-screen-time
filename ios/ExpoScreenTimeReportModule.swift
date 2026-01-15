import ExpoModulesCore

#if canImport(DeviceActivity)
import DeviceActivity
#endif

public class ExpoScreenTimeReportModule: Module {
    public func definition() -> ModuleDefinition {
        Name("ExpoScreenTimeReport")

        View(ExpoScreenTimeReportView.self) {
            Prop("context") { (view: ExpoScreenTimeReportView, context: String) in
                view.setContext(context)
            }

            Prop("filterSegment") { (view: ExpoScreenTimeReportView, segment: String) in
                view.setFilterSegment(segment)
            }

            Prop("filterDate") { (view: ExpoScreenTimeReportView, timestamp: Double) in
                view.setFilterDate(timestamp)
            }
        }
    }
}
