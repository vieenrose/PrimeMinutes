import WidgetKit
import SwiftUI
import AppIntents

/// Home-screen widget + iOS 18 control: one red record button that opens Prime Minutes already recording.
/// Static (no shared state with the app): Stop stays in the app, as with the in-app Record button.
@main struct PrimeWidgets: WidgetBundle {
    var body: some Widget {
        RecordWidget()
        if #available(iOSApplicationExtension 18.0, *) { RecordControl() }
    }
}

struct RecordEntryTimeline: TimelineEntry { let date: Date }
struct RecordProvider: TimelineProvider {
    func placeholder(in context: Context) -> RecordEntryTimeline { .init(date: .now) }
    func getSnapshot(in context: Context, completion: @escaping (RecordEntryTimeline) -> Void) { completion(.init(date: .now)) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<RecordEntryTimeline>) -> Void) { completion(Timeline(entries: [.init(date: .now)], policy: .never)) }
}

struct RecordWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "tw.com.pesi.primeminutes.record", provider: RecordProvider()) { _ in RecordButtonView() }
            .configurationDisplayName(Text("widget_name"))
            .description(Text("widget_desc"))
            .supportedFamilies([.systemSmall])
    }
}

/// The whole widget is the link (primeminutes://record): opening the app is the reliable path on iOS 17 too.
struct RecordButtonView: View {
    var body: some View {
        VStack(spacing: 10) {
            ZStack {
                Circle().fill(Color.red)
                Circle().strokeBorder(Color.white.opacity(0.9), lineWidth: 4).padding(10)
            }
            .frame(width: 84, height: 84)
            .accessibilityHidden(true)
            Text("widget_record").font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
        }
        .widgetURL(URL(string: "primeminutes://record"))
        .containerBackground(.fill.tertiary, for: .widget)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

@available(iOSApplicationExtension 18.0, *)
struct RecordControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "tw.com.pesi.primeminutes.record.control") {
            ControlWidgetButton(action: StartRecordingIntent()) { Label("control_record", systemImage: "record.circle") }
                .tint(.red)
        }
        .displayName("control_name")
        .description("widget_desc")
    }
}
