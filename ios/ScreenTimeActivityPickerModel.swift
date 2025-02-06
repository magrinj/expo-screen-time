import Foundation
import FamilyControls
import Combine

@available(iOS 15.0, *)
class ScreenTimeActivityPickerModel: ObservableObject {
    @Published var activitySelection: FamilyActivitySelection = FamilyActivitySelection()

    @Published public var headerText: String?

    @Published public var footerText: String?
    
    init() {}
}
