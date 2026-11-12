import UIKit

extension UIView {
    /// The nearest parent view controller found by walking the responder chain.
    ///
    /// Replaces React Native's `reactViewController()`, which is not exposed
    /// to Expo views (SDK 56 / RN 0.85 and later). Used to attach hosting controllers as
    /// child view controllers for proper containment.
    var parentViewController: UIViewController? {
        var responder: UIResponder? = next
        while let current = responder {
            if let viewController = current as? UIViewController {
                return viewController
            }
            responder = current.next
        }
        return nil
    }
}
