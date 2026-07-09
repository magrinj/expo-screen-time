import DeviceActivity
import SwiftUI

@main
struct DeviceActivityReportExtensionApp: DeviceActivityReportExtension {
    var body: some DeviceActivityReportScene {
        // Template-only: the report renders the JS-authored template
        // (Report.* JSX serialized through the App Group).
        CustomTemplateReport { templateData in
            CustomTemplateView(data: templateData)
        }
    }
}
