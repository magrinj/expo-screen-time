import Combine
import ExpoModulesCore
import FamilyControls
import SwiftUI
import UIKit

class ExpoScreenTimeSelectAppsView: ExpoView {
    let onSelectionChange = EventDispatcher()
    private let model = ScreenTimeSelectAppsModel()
    private var cancellables = Set<AnyCancellable>()
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    
    required init(appContext: AppContext? = nil) {
        super.init(appContext: appContext)
        clipsToBounds = true

        let selectionView = UIHostingController(
            rootView: ScreenTimeSelectionView(model: model)
        )
        let selectionViewHost = selectionView.view!
        
        addSubview(selectionViewHost)

        selectionViewHost.translatesAutoresizingMaskIntoConstraints = false
        selectionViewHost.centerXAnchor.constraint(equalTo: centerXAnchor)
            .isActive = true
        selectionViewHost.centerYAnchor.constraint(equalTo: centerYAnchor)
            .isActive = true
        selectionViewHost.widthAnchor.constraint(equalTo: widthAnchor)
            .isActive = true
        selectionViewHost.heightAnchor.constraint(equalTo: heightAnchor)
            .isActive = true

        model.$activitySelection
            .sink { [weak self] selection in
                self?.onSelectionChange(selection)
            }
            .store(in: &cancellables)
    }

    private func onSelectionChange(_ selection: FamilyActivitySelection) {
        do {
            let selectionData = try encoder.encode(selection)

            if let selectionJSON = String(data: selectionData, encoding: .utf8)
            {
                self.onSelectionChange(["selection": selectionJSON])
            }
        } catch {
            print("Erreur d'encodage: \(error)")
        }
    }

    func initializeSelection(from json: String) {
        guard let data = json.data(using: .utf8) else { return }

        do {
            let decodedSelection = try decoder.decode(
                FamilyActivitySelection.self, from: data)

            model.activitySelection = decodedSelection
        } catch {
            print("Erreur de décodage: \(error)")
        }
    }
}
