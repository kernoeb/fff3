import AppKit
import SwiftUI

@main
struct Fff3App: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var model = AppModel()

    var body: some Scene {
        Window("fff3", id: "main") {
            ContentView(model: model)
                .frame(minWidth: 640, minHeight: 520)
                .onAppear { delegate.model = model }
        }
        .windowStyle(.hiddenTitleBar)
        .defaultSize(width: 760, height: 600)
        .commands {
            CommandGroup(replacing: .newItem) {}
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    @MainActor var model: AppModel?

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    @MainActor
    func applicationWillTerminate(_ notification: Notification) {
        // Stop the music and free each strip's single connection for the phone.
        model?.shutdown()
    }
}
