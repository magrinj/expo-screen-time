import SwiftUI
import FamilyControls

struct ScreenTimeSelectionView: View {
    @ObservedObject var model: ScreenTimeSelectAppsModel

    var body: some View {
        if #available(iOS 16.0, *) {
            FamilyActivityPicker(headerText: "", footerText: "", selection: $model.activitySelection)
        } else {
            // Fallback on earlier versions
        }
    }
}
