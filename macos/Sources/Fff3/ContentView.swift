import AppKit
import SwiftUI
import Fff3Core

struct ContentView: View {
    @Bindable var model: AppModel
    @Environment(\.colorScheme) private var scheme
    @State private var adding = ProcessInfo.processInfo.environment["FFF3_PAGE"] == "add"

    private var theme: Theme { .of(scheme) }
    private var accent: Color { theme.accent(model.accent) }

    var body: some View {
        HStack(spacing: 0) {
            sidebar
                .frame(width: 210)
            Rectangle().fill(theme.rule).frame(width: 1)
            VStack(spacing: 0) {
                header
                Rectangle().fill(theme.rule).frame(height: 1)
                if adding {
                    AddStripView(model: model, theme: theme, accent: accent) { adding = false }
                } else if model.devices.isEmpty {
                    EmptyState(theme: theme, accent: accent, onAccent: theme.onAccent(model.accent)) { adding = true }
                } else if model.page == .light {
                    LightPage(model: model, theme: theme, accent: accent)
                } else {
                    MusicPage(model: model, theme: theme, accent: accent)
                }
            }
        }
        .foregroundStyle(theme.text)
        // Blur behind everything, with a wash of the theme colour so text keeps its contrast.
        .background {
            Glass()
                .overlay(theme.background.opacity(theme.glassWash))
                .ignoresSafeArea()
        }
        .background(TransparentWindow())
        .animation(.easeOut(duration: 0.25), value: model.accent)
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 2) {
            Color.clear
                .frame(height: 10)
                .background(WindowDragArea())

            if !model.devices.isEmpty {
                SidebarRow(title: "All strips", subtitle: "\(model.devices.count) strip\(model.devices.count == 1 ? "" : "s")",
                           dot: nil, selected: !adding && model.target == .all, theme: theme, accent: accent) {
                    adding = false
                    model.target = .all
                }
            }
            ForEach(model.devices) { device in
                let (color, status) = statusOf(device)
                // Two strips often share the advertised name: the start of the identifier tells them apart.
                let twin = model.devices.contains { $0.id != device.id && $0.displayName == device.displayName }
                let text = twin ? "\(status) · \(device.id.uuidString.prefix(4))" : status
                SidebarRow(title: device.displayName, subtitle: text, dot: color,
                           selected: !adding && model.target == .device(device.id), theme: theme, accent: accent) {
                    adding = false
                    model.target = .device(device.id)
                }
                .contextMenu {
                    Button("Rename…") { rename(device) }
                    Button("Forget strip…", role: .destructive) { forget(device) }
                }
            }
            SidebarRow(title: "Add strip", subtitle: nil, dot: nil, icon: "plus", selected: adding, theme: theme, accent: accent) {
                adding = true
            }
            Spacer()
            bluetoothLine
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
        }
        .padding(.horizontal, 6)
    }

    private var bluetoothLine: some View {
        let (color, text): (Color, String) = switch model.bluetooth {
        case .ready: (theme.ok, "Bluetooth on")
        case .off: (theme.danger, "Bluetooth is off")
        case .permissionMissing: (theme.danger, "Bluetooth not allowed")
        case .unsupported: (theme.danger, "No Bluetooth LE")
        case .unknown: (theme.dim, "Starting Bluetooth")
        }
        return HStack(spacing: 7) {
            StatusDot(color: color)
            Text(text).font(.system(size: 11)).foregroundStyle(theme.dim)
        }
    }

    private func statusOf(_ device: KnownDevice) -> (Color, String) {
        switch model.connection[device.id] ?? .idle {
        case .connected: (theme.ok, "Connected")
        case .connecting(let attempt): (theme.warn, attempt > 1 ? "Connecting, try \(attempt)" : "Connecting")
        case .waitingToRetry(let failures, _): (failures >= 3 ? theme.danger : theme.warn, failures >= 3 ? "Not found. Is another device using it?" : "Retrying soon")
        case .bluetoothOff: (theme.danger, "Bluetooth is off")
        case .permissionMissing: (theme.danger, "Bluetooth not allowed")
        case .unsupported: (theme.danger, "No Bluetooth LE")
        case .idle: (theme.dim, "Not connected")
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Text(adding ? "Add a strip" : headerTitle)
                .font(.system(size: 13, weight: .semibold))
                .lineLimit(1)
            Spacer()
            if !adding && !model.devices.isEmpty {
                PillSwitch(options: Page.allCases.map { ($0, $0.rawValue) }, selection: $model.page, accent: accent, theme: theme)
            }
        }
        .padding(.horizontal, 20)
        .frame(height: 46)
        .background(WindowDragArea())
    }

    private var headerTitle: String {
        switch model.target {
        case .all: "All strips"
        case .device(let id): model.devices.first { $0.id == id }?.displayName ?? "Strip"
        }
    }

    private func rename(_ device: KnownDevice) {
        let alert = NSAlert()
        alert.messageText = "Rename strip"
        let field = NSTextField(string: device.label ?? "")
        field.placeholderString = device.advertisedName
        field.frame = NSRect(x: 0, y: 0, width: 240, height: 24)
        alert.accessoryView = field
        alert.addButton(withTitle: "Rename")
        alert.addButton(withTitle: "Cancel")
        alert.window.initialFirstResponder = field
        if alert.runModal() == .alertFirstButtonReturn { model.rename(device.id, to: field.stringValue) }
    }

    private func forget(_ device: KnownDevice) {
        let alert = NSAlert()
        alert.messageText = "Forget \(device.displayName)?"
        alert.informativeText = "This Mac stops connecting to it. You can add it again with Add strip."
        alert.addButton(withTitle: "Forget")
        alert.addButton(withTitle: "Cancel")
        alert.buttons.first?.hasDestructiveAction = true
        if alert.runModal() == .alertFirstButtonReturn { model.forget(device.id) }
    }
}

private struct SidebarRow: View {
    let title: String
    let subtitle: String?
    let dot: Color?
    var icon: String? = nil
    let selected: Bool
    let theme: Theme
    let accent: Color
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        HStack(spacing: 9) {
            if let icon {
                Image(systemName: icon).font(.system(size: 11, weight: .semibold)).foregroundStyle(theme.dim).frame(width: 10)
            } else if let dot {
                StatusDot(color: dot).frame(width: 10)
            } else {
                Image(systemName: "square.stack").font(.system(size: 10)).foregroundStyle(theme.dim).frame(width: 10)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.system(size: 13, weight: selected ? .semibold : .regular)).lineLimit(1)
                if let subtitle {
                    Text(subtitle).font(.system(size: 11)).foregroundStyle(theme.dim).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(RoundedRectangle(cornerRadius: 7).fill(selected ? accent.opacity(0.16) : theme.text.opacity(hover ? 0.05 : 0)))
        .contentShape(RoundedRectangle(cornerRadius: 7))
        .onTapGesture(perform: action)
        .onHover { hover = $0 }
    }
}

private struct EmptyState: View {
    let theme: Theme
    let accent: Color
    let onAccent: Color
    let add: () -> Void

    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            Text("No strips yet").font(.system(size: 17, weight: .semibold))
            Text("Add your ELK-BLEDOM or MELK strip once. fff3 then connects to it each time it opens.")
                .font(.system(size: 13)).foregroundStyle(theme.dim)
                .multilineTextAlignment(.center).frame(maxWidth: 340)
            QuietButton(title: "Add strip", systemImage: "plus", theme: theme, accent: accent, prominent: true, onAccent: onAccent, action: add)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}

private struct AddStripView: View {
    let model: AppModel
    let theme: Theme
    let accent: Color
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Looking for strips nearby").font(.system(size: 12)).foregroundStyle(theme.dim)
                Spacer()
                QuietButton(title: "Done", theme: theme, action: close)
            }
            let fresh = model.scanned.filter { strip in !model.devices.contains { $0.id == strip.id } }
            if fresh.isEmpty {
                Text("A strip shows here when it is on and no other device is connected to it. Close the fff3 app on your phone first: each strip accepts one connection at a time.")
                    .font(.system(size: 12)).foregroundStyle(theme.dim).fixedSize(horizontal: false, vertical: true)
            }
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(fresh) { strip in
                        ScanRow(strip: strip, theme: theme) { model.add(strip) }
                        Rectangle().fill(theme.rule).frame(height: 1)
                    }
                }
            }
            .scrollContentBackground(.hidden)
        }
        .padding(20)
        .onAppear { model.scanning = true }
        .onDisappear { model.scanning = false }
    }
}

private struct ScanRow: View {
    let strip: ScannedStrip
    let theme: Theme
    let add: () -> Void
    @State private var hover = false

    var body: some View {
        HStack(spacing: 10) {
            SignalBars(rssi: strip.rssi, theme: theme)
            VStack(alignment: .leading, spacing: 1) {
                Text(strip.name.trimmingCharacters(in: .whitespaces)).font(.system(size: 13, weight: .medium))
                Text("\(strip.rssi > -60 ? "Close by" : strip.rssi > -75 ? "In range" : "Far away") · \(strip.id.uuidString.prefix(4))")
                    .font(.system(size: 11)).foregroundStyle(theme.dim)
            }
            Spacer()
            Text("Add").font(.system(size: 12, weight: .medium)).foregroundStyle(hover ? theme.text : theme.dim)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 9)
        .background(theme.text.opacity(hover ? 0.05 : 0))
        .contentShape(Rectangle())
        .onTapGesture(perform: add)
        .onHover { hover = $0 }
    }
}

private struct SignalBars: View {
    let rssi: Int
    let theme: Theme

    var body: some View {
        let level = rssi > -60 ? 3 : rssi > -75 ? 2 : 1
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(1...3, id: \.self) { bar in
                RoundedRectangle(cornerRadius: 1)
                    .fill(bar <= level ? theme.text : theme.rule)
                    .frame(width: 3, height: CGFloat(4 + bar * 3))
            }
        }
        .frame(width: 14, height: 14, alignment: .bottom)
    }
}

/// The title bar is hidden, so these strips are what you drag the window by.
struct WindowDragArea: NSViewRepresentable {
    final class DragView: NSView {
        override var mouseDownCanMoveWindow: Bool { true }
    }

    func makeNSView(context: Context) -> NSView { DragView() }
    func updateNSView(_ view: NSView, context: Context) {}
}
