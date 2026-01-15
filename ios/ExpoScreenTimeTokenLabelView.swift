import ExpoModulesCore
import FamilyControls
import ManagedSettings
import SwiftUI
import UIKit

@available(iOS 15.0, *)
struct TokenLabelContent: View {
    let tokenType: String
    let displayMode: String  // "icon-only", "title-only", "title-and-icon"
    let applicationToken: ApplicationToken?
    let categoryToken: ActivityCategoryToken?
    let webDomainToken: WebDomainToken?

    var body: some View {
        if #available(iOS 15.2, *) {
            switch tokenType {
            case "application":
                if let token = applicationToken {
                    labelView(for: token)
                } else {
                    placeholderText("No app token")
                }
            case "category":
                if let token = categoryToken {
                    labelView(for: token)
                } else {
                    placeholderText("No category token")
                }
            case "webDomain":
                if let token = webDomainToken {
                    labelView(for: token)
                } else {
                    placeholderText("No domain token")
                }
            default:
                Text("Unknown type: \(tokenType)")
                    .foregroundColor(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        } else {
            Text("iOS 15.2+ required")
                .foregroundColor(.gray)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @available(iOS 15.2, *)
    @ViewBuilder
    private func labelView(for token: ApplicationToken) -> some View {
        switch displayMode {
        case "icon-only":
            Label(token)
                .labelStyle(.iconOnly)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case "title-only":
            Label(token)
                .labelStyle(.titleOnly)
                .frame(maxWidth: .infinity, alignment: .leading)
        default:
            Label(token)
                .labelStyle(.titleAndIcon)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @available(iOS 15.2, *)
    @ViewBuilder
    private func labelView(for token: ActivityCategoryToken) -> some View {
        switch displayMode {
        case "icon-only":
            Label(token)
                .labelStyle(.iconOnly)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case "title-only":
            Label(token)
                .labelStyle(.titleOnly)
                .frame(maxWidth: .infinity, alignment: .leading)
        default:
            Label(token)
                .labelStyle(.titleAndIcon)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @available(iOS 15.2, *)
    @ViewBuilder
    private func labelView(for token: WebDomainToken) -> some View {
        switch displayMode {
        case "icon-only":
            Label(token)
                .labelStyle(.iconOnly)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case "title-only":
            Label(token)
                .labelStyle(.titleOnly)
                .frame(maxWidth: .infinity, alignment: .leading)
        default:
            Label(token)
                .labelStyle(.titleAndIcon)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func placeholderText(_ text: String) -> some View {
        Text(text)
            .foregroundColor(.gray)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

@available(iOS 15.0, *)
class ExpoScreenTimeTokenLabelView: ExpoView {
    private var hostingController: UIHostingController<TokenLabelContent>!
    private var tokenType: String = "application"
    private var displayMode: String = "title-and-icon"
    private var selectionId: String?
    private var handle: String?
    private var applicationToken: ApplicationToken?
    private var categoryToken: ActivityCategoryToken?
    private var webDomainToken: WebDomainToken?

    required init(appContext: AppContext? = nil) {
        super.init(appContext: appContext)

        hostingController = UIHostingController(rootView: TokenLabelContent(
            tokenType: tokenType,
            displayMode: displayMode,
            applicationToken: nil,
            categoryToken: nil,
            webDomainToken: nil
        ))
        hostingController.view.backgroundColor = .clear
        self.addSubview(hostingController.view)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        hostingController.view.frame = bounds
    }

    func setTokenType(_ type: String?) {
        tokenType = type ?? "application"
        resolveAndUpdate()
    }

    func setDisplayMode(_ mode: String?) {
        displayMode = mode ?? "title-and-icon"
        updateView()
    }

    func setSelectionId(_ id: String?) {
        selectionId = id
        resolveAndUpdate()
    }

    func setHandle(_ h: String?) {
        handle = h
        resolveAndUpdate()
    }

    private func resolveAndUpdate() {
        // Reset all tokens
        applicationToken = nil
        categoryToken = nil
        webDomainToken = nil

        guard let selectionId = selectionId, let handle = handle else {
            updateView()
            return
        }

        // Lookup via SelectionStore
        switch tokenType {
        case "application":
            applicationToken = SelectionStore.shared.resolveApplicationToken(selectionId, handle: handle)
        case "category":
            categoryToken = SelectionStore.shared.resolveCategoryToken(selectionId, handle: handle)
        case "webDomain":
            webDomainToken = SelectionStore.shared.resolveWebDomainToken(selectionId, handle: handle)
        default:
            break
        }

        updateView()
    }

    private func updateView() {
        hostingController.rootView = TokenLabelContent(
            tokenType: tokenType,
            displayMode: displayMode,
            applicationToken: applicationToken,
            categoryToken: categoryToken,
            webDomainToken: webDomainToken
        )
    }
}
