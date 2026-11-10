import SwiftUI
import Fff3Core

/// Power, colour, brightness, whites and built-in effects for the target strips.
struct LightPage: View {
    let model: AppModel
    let theme: Theme
    let accent: Color
    @State private var brightness = 100.0
    @State private var speed = 50.0

    private var state: LedState { model.targetState }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                HStack(alignment: .top, spacing: 26) {
                    ColorWheel(color: state.effectId == nil && state.sceneId == nil ? state.color : nil, theme: theme) { rgb in
                        model.send([.color(rgb)])
                    }
                    .frame(width: 200, height: 200)
                    VStack(alignment: .leading, spacing: 18) {
                        section("Power") {
                            HStack(spacing: 8) {
                                powerButton(true)
                                powerButton(false)
                            }
                        }
                        section("Brightness", value: "\(Int(brightness)) %") {
                            LineSlider(value: $brightness, range: 1...100, step: 1, accent: accent, theme: theme)
                                .onChange(of: brightness) { _, new in
                                    if Int(new) != state.brightness { model.send([.brightness(Int(new))]) }
                                }
                        }
                        section("White") {
                            HStack(spacing: 8) {
                                swatch("Warm", Rgb.fromKelvin(2700))
                                swatch("Neutral", Rgb.fromKelvin(4000))
                                swatch("Cool", Rgb.fromKelvin(6500))
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                if let family = model.targetFamily {
                    EffectsList(model: model, family: family, theme: theme, accent: accent, speed: $speed)
                } else if model.targetDevices.count > 1 {
                    Text("Effects show when all the strips are of the same kind. Pick one strip to see its effects.")
                        .font(.system(size: 12)).foregroundStyle(theme.dim)
                }
            }
            .padding(20)
        }
        .scrollContentBackground(.hidden)
        .onAppear(perform: syncFromState)
        .onChange(of: model.target) { _, _ in syncFromState() }
    }

    private func syncFromState() {
        brightness = Double(state.brightness ?? 100)
        speed = Double(state.speed ?? 50)
    }

    private func powerButton(_ on: Bool) -> some View {
        let selected = state.power == on
        return Button {
            model.send([.power(on)])
        } label: {
            Text(on ? "On" : "Off")
                .font(.system(size: 12, weight: selected ? .semibold : .medium))
                .frame(width: 64, height: 28)
                .background(RoundedRectangle(cornerRadius: 7).fill(selected ? accent.opacity(0.22) : theme.text.opacity(0.03)))
                .overlay(RoundedRectangle(cornerRadius: 7).strokeBorder(selected ? accent.opacity(0.5) : theme.rule, lineWidth: 1))
                .contentShape(RoundedRectangle(cornerRadius: 7))
        }
        .buttonStyle(.plain)
    }

    private func swatch(_ name: String, _ rgb: Rgb) -> some View {
        let selected = state.color == rgb && state.effectId == nil
        return Button {
            model.send([.color(rgb)])
        } label: {
            HStack(spacing: 6) {
                Circle().fill(Color(rgb)).frame(width: 12, height: 12)
                    .overlay(Circle().strokeBorder(theme.rule, lineWidth: 1))
                Text(name).font(.system(size: 12))
            }
            .padding(.horizontal, 10)
            .frame(height: 28)
            .background(RoundedRectangle(cornerRadius: 7).fill(selected ? accent.opacity(0.16) : .clear))
            .overlay(RoundedRectangle(cornerRadius: 7).strokeBorder(theme.rule, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 7))
        }
        .buttonStyle(.plain)
    }

    private func section<Content: View>(_ title: String, value: String? = nil, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack {
                Text(title).font(.system(size: 11, weight: .semibold)).foregroundStyle(theme.dim)
                Spacer()
                if let value { Text(value).font(.system(size: 11).monospacedDigit()).foregroundStyle(theme.dim) }
            }
            content()
        }
    }
}

/// The family's built-in effects and scenes in groups, with the speed they run at.
private struct EffectsList: View {
    let model: AppModel
    let family: LedFamily
    let theme: Theme
    let accent: Color
    @Binding var speed: Double

    private var groups: [(String, [EffectInfo])] {
        var order: [String] = []
        var byGroup: [String: [EffectInfo]] = [:]
        for effect in family.effects + family.scenes {
            if byGroup[effect.group] == nil { order.append(effect.group) }
            byGroup[effect.group, default: []].append(effect)
        }
        return order.map { ($0, byGroup[$0]!) }
    }

    var body: some View {
        let state = model.targetState
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                Text("Effects").font(.system(size: 11, weight: .semibold)).foregroundStyle(theme.dim)
                Spacer()
                Text("Speed").font(.system(size: 11)).foregroundStyle(theme.dim)
                LineSlider(value: $speed, range: 0...100, step: 1, accent: accent, theme: theme)
                    .frame(width: 160)
                    .onChange(of: speed) { _, new in
                        if state.effectId != nil, Int(new) != state.speed { model.send([.speed(Int(new))]) }
                    }
                Text("\(Int(speed)) %").font(.system(size: 11).monospacedDigit()).foregroundStyle(theme.dim).frame(width: 36, alignment: .trailing)
            }
            ForEach(groups, id: \.0) { group in
                VStack(alignment: .leading, spacing: 6) {
                    Text(group.0).font(.system(size: 12, weight: .medium))
                    FlowLayout(spacing: 6) {
                        ForEach(group.1, id: \.self) { effect in
                            let isScene = family.scenes.contains(effect) && group.0 == "Scene"
                            let selected = isScene ? state.sceneId == effect.id : state.effectId == effect.id
                            EffectChip(name: effect.name, selected: selected, theme: theme, accent: accent) {
                                model.send([isScene ? .scene(effect.id) : .effect(id: effect.id, speed: Int(speed))])
                            }
                        }
                    }
                }
            }
        }
    }
}

private struct EffectChip: View {
    let name: String
    let selected: Bool
    let theme: Theme
    let accent: Color
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Text(name)
            .font(.system(size: 12))
            .foregroundStyle(selected ? theme.text : theme.text.opacity(0.85))
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Capsule().fill(selected ? accent.opacity(0.22) : theme.text.opacity(hover ? 0.07 : 0.02)))
            .overlay(Capsule().strokeBorder(selected ? accent.opacity(0.5) : theme.rule, lineWidth: 1))
            .contentShape(Capsule())
            .onTapGesture(perform: action)
            .onHover { hover = $0 }
    }
}

/// Lays children out left to right, wrapping to a new line when the row is full.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 600
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0, widest: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { x = 0; y += row + spacing; row = 0 }
            x += size.width + spacing
            widest = max(widest, x - spacing)
            row = max(row, size.height)
        }
        return CGSize(width: min(widest, width), height: y + row)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX { x = bounds.minX; y += row + spacing; row = 0 }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            row = max(row, size.height)
        }
    }
}
