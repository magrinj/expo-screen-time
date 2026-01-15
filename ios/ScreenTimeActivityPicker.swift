import SwiftUI
import FamilyControls

@available(iOS 15.0, *)
struct ScreenTimeActivityPicker: View {
    @ObservedObject var model: ScreenTimeActivityPickerModel
    var onAppear: (() -> Void)?
    var onDisappear: (() -> Void)?

    var body: some View {
        if #available(iOS 16.0, *) {
            FamilyActivityPicker(
                headerText: model.headerText,
                footerText: model.footerText,
                selection: $model.activitySelection
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .edgesIgnoringSafeArea(.all)
            .background(.clear)
            .onAppear { onAppear?() }
            .onDisappear { onDisappear?() }
        } else {
            FamilyActivityPicker(
                selection: $model.activitySelection
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .edgesIgnoringSafeArea(.all)
            .background(.clear)
            .onAppear { onAppear?() }
            .onDisappear { onDisappear?() }
        }
    }
}
