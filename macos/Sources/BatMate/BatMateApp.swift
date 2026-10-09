import AppKit
import SwiftUI
import BatMateCore

@MainActor
final class BatteryModel: ObservableObject {
    @Published var records: [BatteryRecord] = []
    @Published var status = "batterySync.mac.waiting"
    private let publisher = SystemBatteryPublisher()
    private var task: Task<Void, Never>?
    private var termination: NSObjectProtocol?

    init() {
        termination = NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification,
            object: nil, queue: .main) { [weak self] _ in MainActor.assumeIsolated { self?.stop() } }
        start()
    }

    func start() {
        task?.cancel()
        task = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                do { try await Task.sleep(nanoseconds: 60_000_000_000) } catch { return }
            }
        }
    }

    private func refresh() async {
        do {
            let snapshot = try await Task.detached { try USBReceiver().read() }.value
            guard !Task.isCancelled else { return }
            let updated = snapshot.records(phoneName: Catalog.text("batterySync.phone"), wearableName: Catalog.text("batterySync.wearable"))
            try publisher.update(updated)
            records = updated
            status = "batterySync.mac.connected"
        } catch {
            guard !Task.isCancelled else { return }
            publisher.withdrawAll()
            records = []
            if case ReceiverError.publishFailed = error { status = "batterySync.mac.systemUnavailable" }
            else { status = "batterySync.mac.unavailable" }
        }
    }

    func stop() { task?.cancel(); task = nil; publisher.withdrawAll() }
}

extension Image {
    static func englishSystemName(_ name: String) -> some View {
        Image(systemName: name).environment(\.locale, Locale(identifier: "en_US"))
    }
}

struct BatMateApp: App {
    @StateObject private var model = BatteryModel()
    var body: some Scene {
        MenuBarExtra {
            Text(Catalog.text(model.status))
            ForEach(model.records, id: \.id) { record in
                Text("\(record.name)  \(record.percent)%  \(Catalog.text(record.charging ? "batterySync.charging" : "batterySync.notCharging"))")
            }
            Divider()
            Button(Catalog.text("batterySync.mac.refresh")) { model.start() }
            Button(Catalog.text("batterySync.mac.quit")) { model.stop(); NSApplication.shared.terminate(nil) }
        } label: {
            Label { Text(Catalog.text("batterySync.title")) } icon: { Image.englishSystemName("battery.100") }
        }
    }
}
