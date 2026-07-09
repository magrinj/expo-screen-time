import Charts
import DeviceActivity
import FamilyControls
import ManagedSettings
import SwiftUI

/// Context rendered when JS supplies a report template (Report.* JSX serialized
/// to JSON and shipped through the App Group). The renderer interprets the
/// versioned node tree — `{ type, props, children }`, same shape as
/// expo-widgets' DynamicView — against the usage data aggregated below. Values
/// never leave the sandbox; JS only ever authored the layout.
extension DeviceActivityReport.Context {
    static let customTemplate = Self("CustomTemplate")
}

// MARK: - Data models

struct TemplateAppData: Identifiable {
    let id: String
    let token: ApplicationToken?
    let name: String
    let duration: TimeInterval
}

struct TemplateCategoryData: Identifiable {
    let id: String
    let token: ActivityCategoryToken?
    let name: String
    let duration: TimeInterval
}

struct CustomTemplateData {
    let totalDuration: TimeInterval
    let pickups: Int
    let longestActivity: TimeInterval
    let appCount: Int
    let firstPickup: Date?
    /// 24 buckets, seconds per hour-of-day (filter uses hourly segments).
    let hourly: [TimeInterval]
    let topApps: [TemplateAppData]
    let categories: [TemplateCategoryData]
    let templateJson: String?
}

// MARK: - Scene

struct CustomTemplateReport: DeviceActivityReportScene {
    let context: DeviceActivityReport.Context = .customTemplate

    let content: (CustomTemplateData) -> CustomTemplateView

    func makeConfiguration(representing data: DeviceActivityResults<DeviceActivityData>) async -> CustomTemplateData {
        var totalDuration: TimeInterval = 0
        var pickups = 0
        var longest: TimeInterval = 0
        var firstPickup: Date?
        var hourly = [TimeInterval](repeating: 0, count: 24)
        var apps: [String: (token: ApplicationToken?, name: String, duration: TimeInterval)] = [:]
        var categories: [String: (token: ActivityCategoryToken?, duration: TimeInterval)] = [:]

        for await d in data {
            for await segment in d.activitySegments {
                totalDuration += segment.totalActivityDuration
                pickups += segment.totalPickupsWithoutApplicationActivity
                longest = max(longest, segment.longestActivity?.duration ?? 0)
                if let pickup = segment.firstPickup {
                    firstPickup = firstPickup.map { min($0, pickup) } ?? pickup
                }

                let hour = Calendar.current.component(.hour, from: segment.dateInterval.start)
                if hour >= 0 && hour < 24 {
                    hourly[hour] += segment.totalActivityDuration
                }

                for await category in segment.categories {
                    let categoryName = category.category.localizedDisplayName ?? "Other"
                    let existingCategory = categories[categoryName]
                    categories[categoryName] = (
                        token: category.category.token ?? existingCategory?.token,
                        duration: (existingCategory?.duration ?? 0) + category.totalActivityDuration
                    )

                    for await app in category.applications {
                        let key = app.application.bundleIdentifier
                            ?? app.application.localizedDisplayName
                            ?? "unknown"
                        let name = app.application.localizedDisplayName ?? "Unknown"
                        let existing = apps[key]
                        apps[key] = (
                            token: app.application.token ?? existing?.token,
                            name: name,
                            duration: (existing?.duration ?? 0) + app.totalActivityDuration
                        )
                    }
                }
            }
        }

        let topApps = apps
            .map { TemplateAppData(id: $0.key, token: $0.value.token, name: $0.value.name, duration: $0.value.duration) }
            .sorted { $0.duration > $1.duration }
        let sortedCategories = categories
            .map { TemplateCategoryData(id: $0.key, token: $0.value.token, name: $0.key, duration: $0.value.duration) }
            .sorted { $0.duration > $1.duration }

        let template = TemplateStore.readTemplate()
        return CustomTemplateData(
            totalDuration: totalDuration,
            pickups: pickups,
            longestActivity: longest,
            appCount: apps.count,
            firstPickup: firstPickup,
            hourly: hourly,
            topApps: topApps,
            categories: sortedCategories,
            templateJson: template
        )
    }
}

/// Reads the serialized template from the App Group (same resolution strategy
/// as ReportConfiguration).
enum TemplateStore {
    static func readTemplate() -> String? {
        if let appGroup = Bundle.main.object(forInfoDictionaryKey: "AppGroup") as? String,
           let defaults = UserDefaults(suiteName: appGroup) {
            return defaults.string(forKey: "expo_st_report_template")
        }
        guard let bundleId = Bundle.main.bundleIdentifier else { return nil }
        let baseBundleId = bundleId
            .replacingOccurrences(of: ".DeviceActivityReportExtension", with: "")
            .replacingOccurrences(of: ".device-activity-report", with: "")
        return UserDefaults(suiteName: "group.\(baseBundleId)")?.string(forKey: "expo_st_report_template")
    }
}

// MARK: - Root view

struct CustomTemplateView: View {
    let data: CustomTemplateData

    var body: some View {
        if let json = data.templateJson,
           let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)),
           let template = object as? [String: Any],
           (template["version"] as? Int) == 1,
           let root = template["root"] as? [String: Any] {
            TemplateNodeView(node: root, data: data, item: nil)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        } else {
            // Fail-open: no/unreadable/newer-version template → explain instead
            // of rendering nothing.
            VStack(spacing: 8) {
                Image(systemName: "square.dashed")
                    .font(.system(size: 32))
                    .foregroundColor(Color(.secondaryLabel))
                Text("No custom template set")
                    .font(.subheadline)
                    .foregroundColor(Color(.secondaryLabel))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

// MARK: - Recursive node renderer

/// Current item when rendering inside TopApps/Categories (item scope).
struct TemplateItem {
    let token: ApplicationToken?
    let categoryToken: ActivityCategoryToken?
    let name: String
    let duration: TimeInterval
    /// 1-based position in the list (bind: "rank").
    let rank: Int
}

struct TemplateNodeView: View {
    let node: [String: Any]
    let data: CustomTemplateData
    let item: TemplateItem?

    private var props: [String: Any] { node["props"] as? [String: Any] ?? [:] }
    private var children: [[String: Any]] { node["children"] as? [[String: Any]] ?? [] }

    var body: some View {
        switch node["type"] as? String {
        case "VStack":
            AnyView(
                VStack(alignment: horizontalAlignment, spacing: cgFloat("spacing")) { childViews }
                    .modifier(BoxModifier(props: props))
            )
        case "HStack":
            AnyView(
                HStack(alignment: verticalAlignment, spacing: cgFloat("spacing")) { childViews }
                    .modifier(BoxModifier(props: props))
            )
        case "ZStack":
            AnyView(ZStack(alignment: zStackAlignment) { childViews }.modifier(BoxModifier(props: props)))
        case "Grid":
            AnyView(
                LazyVGrid(
                    columns: Array(
                        repeating: GridItem(.flexible(), spacing: cgFloat("spacing") ?? 8),
                        count: max((props["columns"] as? NSNumber)?.intValue ?? 2, 1)
                    ),
                    spacing: cgFloat("spacing") ?? 8
                ) { childViews }
                .modifier(BoxModifier(props: props))
            )
        case "Spacer":
            AnyView(Spacer(minLength: cgFloat("minLength")))
        case "Divider":
            AnyView(dividerView)
        case "Text":
            AnyView(textView)
        case "Symbol":
            AnyView(symbolView)
        case "Chart":
            AnyView(chartView)
        case "ProgressBar":
            AnyView(progressBarView)
        case "Gauge":
            AnyView(gaugeView)
        case "TopApps":
            AnyView(itemList(
                data.topApps.enumerated().map {
                    TemplateItem(token: $1.token, categoryToken: nil, name: $1.name, duration: $1.duration, rank: $0 + 1)
                }
            ))
        case "Categories":
            AnyView(itemList(
                data.categories.enumerated().map {
                    TemplateItem(token: nil, categoryToken: $1.token, name: $1.name, duration: $1.duration, rank: $0 + 1)
                }
            ))
        case "AppIcon":
            AnyView(appIconView)
        case "AppTitle":
            AnyView(
                Text(item?.name ?? "")
                    .font(font(defaultSize: 15))
                    .foregroundColor(color("color") ?? Color(.label))
                    .opacity(double("opacity") ?? 1)
                    .lineLimit((props["lineLimit"] as? NSNumber)?.intValue ?? 1)
            )
        default:
            // Unknown node (newer JS than shipped native) — skip, keep rendering.
            AnyView(EmptyView())
        }
    }

    private var childViews: some View {
        ForEach(Array(children.enumerated()), id: \.offset) { _, child in
            TemplateNodeView(node: child, data: data, item: item)
        }
    }

    // MARK: Components

    private var dividerView: some View {
        Rectangle()
            .fill(color("color") ?? Color(.separator))
            .frame(height: cgFloat("thickness") ?? (1 / UIScreen.main.scale))
            .frame(maxWidth: .infinity)
    }

    private var textView: some View {
        let align = props["align"] as? String
        return styledText
            .foregroundColor(color("color") ?? Color(.label))
            .opacity(double("opacity") ?? 1)
            .lineLimit((props["lineLimit"] as? NSNumber)?.intValue)
            .multilineTextAlignment(textAlignment(align))
            .frame(maxWidth: align != nil ? .infinity : nil, alignment: frameAlignment(align))
    }

    /// Text-level styling must happen on `Text` itself (italic/kerning/…);
    /// built imperatively outside any @ViewBuilder.
    private var styledText: Text {
        var text = Text(resolvedText).font(font(defaultSize: 15))
        if props["italic"] as? Bool == true {
            text = text.italic()
        }
        if props["underline"] as? Bool == true {
            text = text.underline()
        }
        if props["strikethrough"] as? Bool == true {
            text = text.strikethrough()
        }
        if props["monospacedDigit"] as? Bool == true {
            text = text.monospacedDigit()
        }
        if let kerning = cgFloat("letterSpacing") {
            text = text.kerning(kerning)
        }
        return text
    }

    private var fontDesign: Font.Design {
        switch props["design"] as? String {
        case "rounded": return .rounded
        case "serif": return .serif
        case "monospaced": return .monospaced
        default: return .default
        }
    }

    private var symbolView: some View {
        Image(systemName: props["name"] as? String ?? "questionmark")
            .font(.system(size: cgFloat("size") ?? 17, weight: fontWeight))
            .symbolRenderingMode(props["renderingMode"] as? String == "hierarchical" ? .hierarchical : .monochrome)
            .foregroundColor(color("color") ?? Color(.label))
            .opacity(double("opacity") ?? 1)
    }

    private var appIconView: some View {
        Group {
            if let token = item?.token {
                Label(token).labelStyle(.iconOnly).scaledToFit()
            } else if let categoryToken = item?.categoryToken {
                Label(categoryToken).labelStyle(.iconOnly).scaledToFit()
            } else {
                RoundedRectangle(cornerRadius: 6)
                    .fill(Color(.tertiarySystemFill))
                    .overlay(Image(systemName: "app.fill").foregroundColor(Color(.systemGray)))
            }
        }
        .frame(width: cgFloat("size") ?? 28, height: cgFloat("size") ?? 28)
        .clipShape(RoundedRectangle(cornerRadius: cgFloat("cornerRadius") ?? 0))
    }

    @ViewBuilder private func itemList(_ items: [TemplateItem]) -> some View {
        let limit = (props["limit"] as? NSNumber)?.intValue ?? 5
        if items.isEmpty, let emptyText = props["emptyText"] as? String {
            Text(emptyText)
                .font(.subheadline)
                .foregroundColor(Color(.secondaryLabel))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
        } else {
            VStack(alignment: .leading, spacing: cgFloat("spacing") ?? 8) {
                ForEach(Array(items.prefix(limit).enumerated()), id: \.offset) { _, entry in
                    ForEach(Array(children.enumerated()), id: \.offset) { _, child in
                        TemplateNodeView(node: child, data: data, item: entry)
                    }
                }
            }
        }
    }

    private var progressBarView: some View {
        let goal = double("goal") ?? 1
        let ratio = goal > 0 ? min(boundValue / goal, 1) : 0
        let height = cgFloat("height") ?? 8
        let radius = cgFloat("cornerRadius") ?? height / 2
        let fill = color("color") ?? .blue
        let track = color("trackColor") ?? Color(.tertiarySystemFill)

        return GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: radius).fill(track)
                RoundedRectangle(cornerRadius: radius)
                    .fill(fill)
                    .frame(width: max(geo.size.width * ratio, ratio > 0 ? height : 0))
            }
        }
        .frame(height: height)
    }

    @ViewBuilder private var gaugeView: some View {
        let goal = double("goal") ?? 1
        let ratio = goal > 0 ? min(boundValue / goal, 1) : 0
        let size = cgFloat("size") ?? 56
        let tint = color("color") ?? .blue
        // Accessory circular gauges render at a FIXED natural size (~56pt) —
        // .frame() alone positions but doesn't shrink them, so small sizes get
        // clipped to an arc. Render at natural size, then scale to the request.
        let naturalSize: CGFloat = 56

        if props["style"] as? String == "linear" {
            Gauge(value: ratio) { EmptyView() }
                .gaugeStyle(.accessoryLinearCapacity)
                .tint(tint)
        } else {
            Gauge(value: ratio) {
                EmptyView()
            } currentValueLabel: {
                Text("\(Int(ratio * 100))%")
                    .font(.system(size: naturalSize * 0.24, weight: .semibold))
            }
            .gaugeStyle(.accessoryCircularCapacity)
            .tint(tint)
            .frame(width: naturalSize, height: naturalSize)
            .scaleEffect(size / naturalSize)
            .frame(width: size, height: size)
        }
    }

    /// Numeric value of this node's binding (seconds for durations).
    private var boundValue: Double {
        switch props["bind"] as? String {
        case "pickups": return Double(data.pickups)
        case "longestActivity": return data.longestActivity
        case "appCount": return Double(data.appCount)
        case "appDuration": return item?.duration ?? 0
        default: return data.totalDuration
        }
    }

    private var resolvedText: String {
        var value: String
        if let bind = props["bind"] as? String {
            // format="percent" renders any numeric binding as % of `goal`.
            if props["format"] as? String == "percent" {
                let goal = double("goal") ?? 1
                let ratio = goal > 0 ? min(boundValue / goal, 1) : 0
                value = "\(Int((ratio * 100).rounded()))%"
            } else {
                switch bind {
                case "totalDuration": value = formatDuration(data.totalDuration)
                case "pickups": value = "\(data.pickups)"
                case "longestActivity": value = formatDuration(data.longestActivity)
                case "appCount": value = "\(data.appCount)"
                case "firstPickup":
                    if let date = data.firstPickup {
                        let formatter = DateFormatter()
                        formatter.timeStyle = .short
                        formatter.dateStyle = .none
                        value = formatter.string(from: date)
                    } else {
                        value = "—"
                    }
                case "appDuration": value = formatDuration(item?.duration ?? 0)
                case "appName": value = item?.name ?? ""
                case "rank": value = "\(item?.rank ?? 0)"
                default: value = ""
                }
            }
        } else {
            value = props["text"] as? String ?? ""
        }
        if props["uppercase"] as? Bool == true {
            value = value.uppercased()
        }
        return value
    }

    @ViewBuilder private var chartView: some View {
        let accent = color("accentColor") ?? .blue
        let height = cgFloat("height") ?? 120
        let bind = props["bind"] as? String ?? "hourly"
        let type = props["type"] as? String ?? "bar"
        let maxItems = (props["maxItems"] as? NSNumber)?.intValue ?? 6
        let barRadius = cgFloat("barCornerRadius") ?? 0
        let lineWidth = cgFloat("lineWidth") ?? 2
        let showPoints = props["showPoints"] as? Bool == true
        let gradient = props["gradient"] as? Bool == true
        let palette = (props["colorPalette"] as? [String])?.compactMap { Color(hex: $0) } ?? []
        // hourly hides the X axis by default (24 cramped labels); lists show it.
        let showXAxis = props["showXAxis"] as? Bool ?? (bind != "hourly")
        let showYAxis = props["showYAxis"] as? Bool ?? true
        let xStride = (props["xAxisStride"] as? NSNumber)?.intValue ?? 6

        let entries: [(label: String, value: Double)] = {
            switch bind {
            case "topApps":
                return data.topApps.prefix(maxItems).map { ($0.name, $0.duration / 60) }
            case "categories":
                return data.categories.prefix(maxItems).map { ($0.name, $0.duration / 60) }
            default:
                return data.hourly.enumerated().map { ("\($0.offset)", $0.element / 60) }
            }
        }()
        let average = entries.isEmpty ? 0 : entries.map(\.value).reduce(0, +) / Double(entries.count)

        let fillStyle: AnyShapeStyle = gradient
            ? AnyShapeStyle(LinearGradient(
                colors: [accent, accent.opacity(0.25)],
                startPoint: .top,
                endPoint: .bottom
              ))
            : AnyShapeStyle(accent)

        let entryColor: (Int) -> Color = { index in
            palette.isEmpty ? accent : palette[index % palette.count]
        }

        Chart {
            if type == "pie", #available(iOS 17.0, *) {
                ForEach(Array(entries.enumerated()), id: \.offset) { index, entry in
                    if palette.isEmpty {
                        SectorMark(
                            angle: .value("Minutes", max(entry.value, 0.0001)),
                            innerRadius: .ratio(Double(cgFloat("pieInnerRadius") ?? 0.55))
                        )
                        .foregroundStyle(by: .value("Label", entry.label))
                    } else {
                        SectorMark(
                            angle: .value("Minutes", max(entry.value, 0.0001)),
                            innerRadius: .ratio(Double(cgFloat("pieInnerRadius") ?? 0.55))
                        )
                        .foregroundStyle(entryColor(index))
                    }
                }
            } else if type == "line" {
                ForEach(Array(entries.enumerated()), id: \.offset) { index, entry in
                    LineMark(x: .value("Label", index), y: .value("Minutes", entry.value))
                        .interpolationMethod(.catmullRom)
                        .lineStyle(StrokeStyle(lineWidth: lineWidth))
                        .foregroundStyle(accent)
                    if showPoints {
                        PointMark(x: .value("Label", index), y: .value("Minutes", entry.value))
                            .foregroundStyle(accent)
                    }
                }
            } else if type == "area" {
                ForEach(Array(entries.enumerated()), id: \.offset) { index, entry in
                    AreaMark(x: .value("Label", index), y: .value("Minutes", entry.value))
                        .interpolationMethod(.catmullRom)
                        .foregroundStyle(fillStyle)
                }
            } else {
                ForEach(Array(entries.enumerated()), id: \.offset) { index, entry in
                    // Hourly uses a numeric X so axis strides apply; lists are categorical.
                    if bind == "hourly" {
                        BarMark(x: .value("Hour", index), y: .value("Minutes", entry.value))
                            .foregroundStyle(palette.isEmpty ? fillStyle : AnyShapeStyle(entryColor(index)))
                            .cornerRadius(barRadius)
                    } else {
                        BarMark(x: .value("Label", entry.label), y: .value("Minutes", entry.value))
                            .foregroundStyle(palette.isEmpty ? fillStyle : AnyShapeStyle(entryColor(index)))
                            .cornerRadius(barRadius)
                    }
                }
            }
            if props["averageLine"] as? Bool == true, average > 0 {
                RuleMark(y: .value("Average", average))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                    .foregroundStyle(accent.opacity(0.7))
            }
            if let goal = double("goalValue"), goal > 0 {
                RuleMark(y: .value("Goal", goal))
                    .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [6, 3]))
                    .foregroundStyle(color("goalColor") ?? Color(.systemRed))
            }
        }
        .chartXAxis {
            if showXAxis {
                if bind == "hourly" {
                    AxisMarks(values: .stride(by: Double(max(xStride, 1)))) { _ in
                        AxisGridLine()
                        AxisValueLabel()
                            .foregroundStyle(color("labelColor") ?? Color(.secondaryLabel))
                    }
                } else {
                    AxisMarks { _ in
                        AxisGridLine()
                        AxisValueLabel()
                            .foregroundStyle(color("labelColor") ?? Color(.secondaryLabel))
                    }
                }
            }
        }
        .chartYAxis {
            if showYAxis {
                AxisMarks { _ in
                    AxisGridLine()
                    AxisValueLabel()
                        .foregroundStyle(color("labelColor") ?? Color(.secondaryLabel))
                }
            }
        }
        .frame(height: height)
    }

    // MARK: Prop helpers

    private func cgFloat(_ key: String) -> CGFloat? {
        (props[key] as? NSNumber).map { CGFloat(truncating: $0) }
    }

    private func double(_ key: String) -> Double? {
        (props[key] as? NSNumber)?.doubleValue
    }

    private func color(_ key: String) -> Color? {
        (props[key] as? String).flatMap { Color(hex: $0) }
    }

    private var fontWeight: Font.Weight {
        switch props["weight"] as? String {
        case "bold": return .bold
        case "semibold": return .semibold
        case "medium": return .medium
        case "heavy": return .heavy
        case "light": return .light
        default: return .regular
        }
    }

    private func font(defaultSize: CGFloat) -> Font {
        .system(size: cgFloat("size") ?? defaultSize, weight: fontWeight, design: fontDesign)
    }

    private var horizontalAlignment: HorizontalAlignment {
        switch props["alignment"] as? String {
        case "leading": return .leading
        case "trailing": return .trailing
        default: return .center
        }
    }

    private var verticalAlignment: VerticalAlignment {
        switch props["alignment"] as? String {
        case "top": return .top
        case "bottom": return .bottom
        default: return .center
        }
    }

    private var zStackAlignment: Alignment {
        switch props["alignment"] as? String {
        case "topLeading": return .topLeading
        case "topTrailing": return .topTrailing
        case "bottomLeading": return .bottomLeading
        case "bottomTrailing": return .bottomTrailing
        default: return .center
        }
    }

    private func textAlignment(_ align: String?) -> TextAlignment {
        switch align {
        case "leading": return .leading
        case "trailing": return .trailing
        default: return .center
        }
    }

    private func frameAlignment(_ align: String?) -> Alignment {
        switch align {
        case "leading": return .leading
        case "trailing": return .trailing
        default: return .center
        }
    }

    private func formatDuration(_ duration: TimeInterval) -> String {
        let style: DateComponentsFormatter.UnitsStyle
        switch props["durationStyle"] as? String {
        case "short": style = .short
        case "full": style = .full
        default: style = .abbreviated
        }
        let formatter = DateComponentsFormatter()
        formatter.unitsStyle = style
        formatter.allowedUnits = duration >= 3600 ? [.hour, .minute] : [.minute]
        formatter.zeroFormattingBehavior = .dropLeading
        return formatter.string(from: max(duration, 0)) ?? "0m"
    }
}

// MARK: - Color hex parsing (was in ReportConfiguration.swift)

extension Color {
    init?(hex: String) {
        var hexSanitized = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        hexSanitized = hexSanitized.replacingOccurrences(of: "#", with: "")

        var rgb: UInt64 = 0
        guard Scanner(string: hexSanitized).scanHexInt64(&rgb) else {
            return nil
        }

        let length = hexSanitized.count
        if length == 6 {
            let r = Double((rgb & 0xFF0000) >> 16) / 255.0
            let g = Double((rgb & 0x00FF00) >> 8) / 255.0
            let b = Double(rgb & 0x0000FF) / 255.0
            self.init(red: r, green: g, blue: b)
        } else if length == 8 {
            let r = Double((rgb & 0xFF000000) >> 24) / 255.0
            let g = Double((rgb & 0x00FF0000) >> 16) / 255.0
            let b = Double((rgb & 0x0000FF00) >> 8) / 255.0
            let a = Double(rgb & 0x000000FF) / 255.0
            self.init(red: r, green: g, blue: b, opacity: a)
        } else {
            return nil
        }
    }
}

/// Shared frame/decoration for stack nodes.
private struct BoxModifier: ViewModifier {
    let props: [String: Any]

    private func cgFloat(_ key: String) -> CGFloat? {
        (props[key] as? NSNumber).map { CGFloat(truncating: $0) }
    }

    private func color(_ key: String) -> Color? {
        (props[key] as? String).flatMap { Color(hex: $0) }
    }

    private var backgroundStyle: AnyShapeStyle {
        if let stops = props["backgroundGradient"] as? [String],
           stops.count >= 2,
           let start = Color(hex: stops[0]),
           let end = Color(hex: stops[1]) {
            let direction = props["gradientDirection"] as? String
            let (startPoint, endPoint): (UnitPoint, UnitPoint) = {
                switch direction {
                case "horizontal": return (.leading, .trailing)
                case "diagonal": return (.topLeading, .bottomTrailing)
                default: return (.top, .bottom)
                }
            }()
            return AnyShapeStyle(LinearGradient(colors: [start, end], startPoint: startPoint, endPoint: endPoint))
        }
        return AnyShapeStyle(color("backgroundColor") ?? Color.clear)
    }

    func body(content: Content) -> some View {
        let radius = cgFloat("cornerRadius") ?? 0
        return content
            .padding(.horizontal, cgFloat("paddingHorizontal") ?? 0)
            .padding(.vertical, cgFloat("paddingVertical") ?? 0)
            .padding(cgFloat("padding") ?? 0)
            .frame(width: cgFloat("width"), height: cgFloat("height"))
            .frame(
                maxWidth: (props["fillWidth"] as? Bool == true) ? .infinity : nil,
                minHeight: cgFloat("minHeight")
            )
            .background(backgroundStyle)
            .cornerRadius(radius)
            .overlay(
                RoundedRectangle(cornerRadius: radius)
                    .stroke(color("borderColor") ?? Color.clear, lineWidth: cgFloat("borderWidth") ?? 0)
            )
            .shadow(
                color: color("shadowColor") ?? .clear,
                radius: cgFloat("shadowRadius") ?? 0,
                x: 0,
                y: cgFloat("shadowY") ?? 0
            )
            .opacity((props["opacity"] as? NSNumber)?.doubleValue ?? 1)
    }
}
