import Foundation
import FamilyControls
import Combine

class ScreenTimeSelectAppsModel: ObservableObject {
    @Published var activitySelection: FamilyActivitySelection = FamilyActivitySelection(includeEntireCategory: true)
    
    init() {}
}
