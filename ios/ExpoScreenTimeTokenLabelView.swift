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

    /// Resolve `handle` to a token. A handle is the token's own encoding
    /// (`ShieldToken.encoded.token`, what `SelectionStore` hands out), so it
    /// is self-describing: without a `selectionId` the view decodes it
    /// directly — no store read, no search, pure and O(1) per label — which
    /// is what lets a handle be labelled wherever it came from (a handle the
    /// app persisted, a token removed from its selection since). With a
    /// `selectionId`, the handle is looked up inside that selection as
    /// before, so a handle that is not in it still renders the placeholder.
    /// A handle this build cannot decode renders the placeholder, never
    /// crashes.
    ///
    /// The decoder cannot check the kind: every token kind is the same
    /// `Token<T>` struct with one Codable shape, so an application handle
    /// decodes as a (bogus) category token too. `tokenType` must be the
    /// handle's own `TokenHandle.type` — the selection lookup used to
    /// enforce that for free; the direct decode cannot.
    private func resolveAndUpdate() {
        // Reset all tokens
        applicationToken = nil
        categoryToken = nil
        webDomainToken = nil

        guard let handle = handle else {
            updateView()
            return
        }

        if let selectionId = selectionId {
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
        } else {
            // Decode the handle itself (kind unchecked — see above).
            switch ShieldToken(kind: tokenType, encoded: handle) {
            case .application(let token)?:
                applicationToken = token
            case .category(let token)?:
                categoryToken = token
            case .webDomain(let token)?:
                webDomainToken = token
            case nil:
                break
            }
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
