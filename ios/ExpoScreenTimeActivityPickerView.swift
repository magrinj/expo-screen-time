import Combine
import ExpoModulesCore
import FamilyControls
import SwiftUI
import UIKit

@available(iOS 15.0, *)
class ExpoScreenTimeActivityPickerView: ExpoView {
    private let contentView: UIHostingController<ScreenTimeActivityPicker>
    private let model = ScreenTimeActivityPickerModel()
    
    let onSelectionChange = EventDispatcher()
    private var cancellables = Set<AnyCancellable>()
    
    required init(appContext: AppContext? = nil) {
        contentView = UIHostingController(rootView: ScreenTimeActivityPicker(model: model))

        super.init(appContext: appContext)

        clipsToBounds = true
        
        backgroundColor = .clear
        isUserInteractionEnabled = false
        
        contentView.view.backgroundColor = .clear
        contentView.view.isUserInteractionEnabled = false
        
        self.addSubview(contentView.view)

        model.$activitySelection
            .sink { [weak self] selection in
                self?.onSelectionChange(selection)
            }
            .store(in: &cancellables)
    }
    
    override func layoutSubviews() {
        contentView.view.frame = bounds
    }

    private func onSelectionChange(_ selection: FamilyActivitySelection) {
        if let selectionObject = serializeFamilyActivitySelection(from: selection) {
            self.onSelectionChange(["selection": selectionObject])
        }
    }

    func initializeSelection(from selectionObject: [String: Any]) {
        do {
            let activitySelection = try deserializeFamilyActivitySelection(from: selectionObject)

            model.activitySelection = activitySelection
        } catch {
            print("Deserialization error: \(error)")
        }
    }
    
    func setHeaderText(_ text: String?) {
        model.headerText = text
    }
    
    func setFooterText(_ text: String?) {
        model.footerText = text
    }
}
