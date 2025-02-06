import SwiftUI
import FamilyControls

@available(iOS 15.0, *)
struct ScreenTimeActivityPicker: View {
    @ObservedObject var model: ScreenTimeActivityPickerModel
    
    var body: some View {
        if #available(iOS 16.0, *) {
            FamilyActivityPicker(
                headerText: $model.headerText.wrappedValue,
                footerText: $model.footerText.wrappedValue,
                selection: $model.activitySelection
            )
            .allowsHitTesting(false)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .edgesIgnoringSafeArea(.all)
            .background(.clear)
        } else {
            FamilyActivityPicker(
                selection: $model.activitySelection
            )
            .allowsHitTesting(false)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .edgesIgnoringSafeArea(.all)
            .background(.clear)
        }
    }
}
