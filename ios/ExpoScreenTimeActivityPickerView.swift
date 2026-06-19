import Combine
import ExpoModulesCore
import FamilyControls
import SwiftUI
import UIKit

@available(iOS 15.0, *)
class ExpoScreenTimeActivityPickerView: ExpoView {
    private var hostingController: UIHostingController<ScreenTimeActivityPicker>!
    private let model = ScreenTimeActivityPickerModel()

    let onSelectionChange = EventDispatcher()
    let onOpen = EventDispatcher()
    let onClose = EventDispatcher()
    private var cancellables = Set<AnyCancellable>()
    private var selectionId: String?

    required init(appContext: AppContext? = nil) {
        super.init(appContext: appContext)

        let picker = ScreenTimeActivityPicker(
            model: model,
            onAppear: { [weak self] in
                self?.onOpen()
            },
            onDisappear: { [weak self] in
                self?.onClose()
            }
        )
        hostingController = UIHostingController(rootView: picker)
        hostingController.view.backgroundColor = .systemBackground

        self.addSubview(hostingController.view)

        model.$activitySelection
            .dropFirst() // Skip initial empty value
            .sink { [weak self] selection in
                self?.handleSelectionChange(selection)
            }
            .store(in: &cancellables)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        hostingController.view.frame = bounds
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if let parentVC = self.parentViewController {
            parentVC.addChild(hostingController)
            hostingController.didMove(toParent: parentVC)
        }
    }

    private func handleSelectionChange(_ selection: FamilyActivitySelection) {
        guard let selectionId = self.selectionId else { return }

        // Save to SelectionStore
        SelectionStore.shared.save(selectionId, selection)

        // Build metadata
        let metadata: [String: Int] = [
            "applicationCount": selection.applicationTokens.count,
            "categoryCount": selection.categoryTokens.count,
            "webDomainCount": selection.webDomainTokens.count,
        ]

        // Build token handles
        let tokens = SelectionStore.shared.getTokenHandles(selectionId) ?? []

        // Emit single event with metadata + handles
        onSelectionChange([
            "metadata": metadata,
            "tokens": tokens,
        ])
    }

    func setSelectionId(_ id: String?) {
        self.selectionId = id
        guard let id = id else {
            model.activitySelection = FamilyActivitySelection()
            return
        }

        if let selection = SelectionStore.shared.get(id) {
            model.activitySelection = selection
        } else {
            model.activitySelection = FamilyActivitySelection()
        }
    }

    func setHeaderText(_ text: String?) {
        model.headerText = text
    }

    func setFooterText(_ text: String?) {
        model.footerText = text
    }
}
