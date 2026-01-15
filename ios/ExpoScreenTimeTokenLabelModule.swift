import ExpoModulesCore
import FamilyControls
import ManagedSettings

public class ExpoScreenTimeTokenLabelModule: Module {
    public func definition() -> ModuleDefinition {
        Name("ExpoScreenTimeTokenLabel")

        View(ExpoScreenTimeTokenLabelView.self) {
            Prop("tokenType") { (view: ExpoScreenTimeTokenLabelView, tokenType: String?) in
                view.setTokenType(tokenType)
            }

            Prop("displayMode") { (view: ExpoScreenTimeTokenLabelView, displayMode: String?) in
                view.setDisplayMode(displayMode)
            }

            Prop("selectionId") { (view: ExpoScreenTimeTokenLabelView, selectionId: String?) in
                view.setSelectionId(selectionId)
            }

            Prop("handle") { (view: ExpoScreenTimeTokenLabelView, handle: String?) in
                view.setHandle(handle)
            }
        }
    }
}
