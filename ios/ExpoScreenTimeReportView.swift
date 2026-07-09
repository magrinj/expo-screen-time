import ExpoModulesCore
import SwiftUI
import UIKit

// Conditionally import DeviceActivity for iOS 16+
#if canImport(DeviceActivity)
import DeviceActivity
#endif

// MARK: - Observable Model

class ReportViewModel: ObservableObject {
    @Published var filterSegment: String = "daily"
    @Published var filterDate: Date = Date()
    /// Hash of the active report template. Applied as the SwiftUI view identity
    /// so a template change forces the extension to re-read the App Group.
    @Published var templateHash: String = ""
}

// MARK: - SwiftUI Container

@available(iOS 16.0, *)
struct ReportContainerView: View {
    @ObservedObject var model: ReportViewModel

    var body: some View {
        // Template-only: every report renders the CustomTemplate scene against
        // the template shipped through the App Group.
        let context = DeviceActivityReport.Context(rawValue: "CustomTemplate")
        let filter = Self.createFilter(
            segment: model.filterSegment,
            date: model.filterDate
        )

        DeviceActivityReport(context, filter: filter)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // Display-only: gestures over the report are captured by the
            // extension process (Apple-confirmed cross-process limitation) —
            // disable interactivity through every channel SwiftUI offers, so
            // the surrounding RN ScrollView keeps the pan whenever possible.
            .allowsHitTesting(false)
            .disabled(true)
            // Recreate (→ re-invoke the extension) when the template changes.
            .id(model.templateHash)
    }

    static func createFilter(segment: String, date: Date) -> DeviceActivityFilter {
        let calendar = Calendar.current

        // Determine the date interval based on segment
        let interval: DateInterval
        switch segment {
        case "daily":
            let startOfDay = calendar.startOfDay(for: date)
            let endOfDay = calendar.date(byAdding: .day, value: 1, to: startOfDay) ?? date
            interval = DateInterval(start: startOfDay, end: endOfDay)
        case "weekly":
            let startOfWeek = calendar.date(from: calendar.dateComponents([.yearForWeekOfYear, .weekOfYear], from: date)) ?? date
            let endOfWeek = calendar.date(byAdding: .weekOfYear, value: 1, to: startOfWeek) ?? date
            interval = DateInterval(start: startOfWeek, end: endOfWeek)
        default:
            let startOfDay = calendar.startOfDay(for: Date())
            let endOfDay = calendar.date(byAdding: .day, value: 1, to: startOfDay) ?? Date()
            interval = DateInterval(start: startOfDay, end: endOfDay)
        }

        // Templates bind hourly buckets — always use hourly segments.
        return DeviceActivityFilter(segment: .hourly(during: interval))
    }
}

// MARK: - ExpoView

/// Transparent layer over the report that manually drives the enclosing
/// UIScrollView from RAW touches (no gesture recognizer). DeviceActivityReport
/// is remote content whose region is gated by the system BELOW any public
/// hit-testing/interaction flag (Apple-confirmed limitation) — scroll pans
/// over it get swallowed no matter what the host disables. Raw touch handling
/// on a locally hit-tested view still receives events, so this forwarder
/// scrolls the ancestor scroll view itself, with UIKit-style projected
/// deceleration. If the native pan DOES win the race, it cancels these touches
/// (canCancelContentTouches) — so the two drivers never fight.
private final class ReportScrollForwarder: UIView {
    private weak var scrollView: UIScrollView?
    private var lastY: CGFloat = 0
    private var lastTimestamp: TimeInterval = 0
    private var velocity: CGFloat = 0

    private func findScrollView() -> UIScrollView? {
        var view: UIView? = superview
        while let current = view {
            if let scroll = current as? UIScrollView { return scroll }
            view = current.superview
        }
        return nil
    }

    /// Re-applied at touch time by the owning view: the remote content is
    /// recreated whenever the template changes, and a freshly attached remote
    /// view hasn't been neutered yet — touch-down is the perfect moment.
    var onTouchBegan: (() -> Void)?

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesBegan(touches, with: event)
        onTouchBegan?()
        scrollView = findScrollView()
        guard let touch = touches.first, let window else { return }
        lastY = touch.location(in: window).y
        lastTimestamp = touch.timestamp
        velocity = 0
        // Grab an in-flight deceleration, like a native touch-down would.
        if let scroll = scrollView, scroll.isDecelerating {
            scroll.setContentOffset(scroll.contentOffset, animated: false)
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesMoved(touches, with: event)
        guard let scroll = scrollView, let touch = touches.first, let window else { return }
        // The native pan took over — let it drive.
        if scroll.isDragging { return }

        let y = touch.location(in: window).y
        let dy = y - lastY
        let dt = touch.timestamp - lastTimestamp
        if dt > 0 { velocity = dy / CGFloat(dt) }
        lastY = y
        lastTimestamp = touch.timestamp

        scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: clampedOffset(scroll, scroll.contentOffset.y - dy)), animated: false)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesEnded(touches, with: event)
        decelerate()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesCancelled(touches, with: event)
        velocity = 0
    }

    private func decelerate() {
        guard let scroll = scrollView, abs(velocity) > 60 else { velocity = 0; return }
        // UIKit's standard momentum projection: distance = (v/1000) * r / (1 - r)
        let rate = UIScrollView.DecelerationRate.normal.rawValue
        let projected = (velocity / 1000) * rate / (1 - rate)
        let target = clampedOffset(scroll, scroll.contentOffset.y - projected)
        velocity = 0
        UIView.animate(
            withDuration: 0.45,
            delay: 0,
            options: [.curveEaseOut, .allowUserInteraction, .beginFromCurrentState]
        ) {
            scroll.contentOffset = CGPoint(x: scroll.contentOffset.x, y: target)
        }
    }

    private func clampedOffset(_ scroll: UIScrollView, _ y: CGFloat) -> CGFloat {
        let minY = -scroll.adjustedContentInset.top
        let maxY = max(minY, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
        return min(max(y, minY), maxY)
    }
}

/// View for displaying DeviceActivityReport.
/// Works on all iOS versions but only shows actual report on iOS 16+.
/// The report is display-only: user interaction is disabled natively so touches
/// fall through to the surrounding RN ScrollView (DeviceActivityReport is a
/// remote view — RN's pointerEvents="none" alone doesn't stop it from
/// capturing pan gestures).
class ExpoScreenTimeReportView: ExpoView {
    private var hostingController: UIHostingController<AnyView>?
    private let viewModel = ReportViewModel()

    /// Transparent view layered ON TOP of the report. Wins local hit-testing
    /// AND drives the enclosing scroll view from raw touches — see
    /// ReportScrollForwarder for why passive pass-through is not enough.
    private let touchShield = ReportScrollForwarder()

    required init(appContext: AppContext? = nil) {
        super.init(appContext: appContext)
        buildHostingController()
        touchShield.backgroundColor = .clear
        touchShield.isUserInteractionEnabled = true
        // Freshly attached remote content (e.g. after a template change) may
        // not be neutered yet — do it right when a touch lands on the shield.
        touchShield.onTouchBegan = { [weak self] in
            self?.neuterHostedSubviews()
        }
        addSubview(touchShield)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        hostingController?.view.frame = bounds
        touchShield.frame = bounds
        bringSubviewToFront(touchShield)
        neuterHostedSubviews()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if let parentVC = self.parentViewController, let hosting = hostingController {
            if hosting.parent == nil {
                parentVC.addChild(hosting)
                hosting.didMove(toParent: parentVC)
            }
        }
        scheduleNeuterSweeps()
    }

    /// The report's remote content (extension process) attaches asynchronously
    /// and its region can capture gestures at the system level, racing the
    /// surrounding scroll view (Apple-confirmed limitation for
    /// DeviceActivityReport). Defense in depth: disable interaction and gesture
    /// recognizers on every hosted descendant — including the remote view once
    /// it appears — and re-apply after attach-latency delays.
    private func neuterHostedSubviews() {
        guard let root = hostingController?.view else { return }
        root.isUserInteractionEnabled = false
        var stack: [UIView] = [root]
        while let view = stack.popLast() {
            for subview in view.subviews {
                subview.isUserInteractionEnabled = false
                subview.gestureRecognizers?.forEach { $0.isEnabled = false }
                stack.append(subview)
            }
        }
    }

    private func scheduleNeuterSweeps() {
        for delay in [0.5, 1.5, 3.0] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                self?.neuterHostedSubviews()
            }
        }
    }

    // MARK: - Props

    func setFilterSegment(_ segment: String) {
        viewModel.filterSegment = segment
    }

    func setFilterDate(_ timestamp: Double) {
        viewModel.filterDate = Date(timeIntervalSince1970: timestamp / 1000)
    }

    func setTemplateHash(_ hash: String?) {
        viewModel.templateHash = hash ?? ""
        // A hash change recreates the DeviceActivityReport (new .id) — the new
        // remote content attaches asynchronously; sweep it too.
        scheduleNeuterSweeps()
    }

    // MARK: - Private

    private func buildHostingController() {
        let wrappedView: AnyView

        if #available(iOS 16.0, *) {
            wrappedView = AnyView(ReportContainerView(model: viewModel))
        } else {
            wrappedView = AnyView(
                Text("DeviceActivityReport requires iOS 16+")
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            )
        }

        let hosting = UIHostingController(rootView: wrappedView)
        hosting.view.backgroundColor = .clear
        // Display-only: let touches fall through to the RN ScrollView above.
        hosting.view.isUserInteractionEnabled = false
        hostingController = hosting

        addSubview(hosting.view)
        hosting.view.frame = bounds

        if let parentVC = self.parentViewController {
            parentVC.addChild(hosting)
            hosting.didMove(toParent: parentVC)
        }
    }
}
