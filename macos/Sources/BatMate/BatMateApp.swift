import AppKit
import SwiftUI
import CoreImage.CIFilterBuiltins
import BatMateCore

@MainActor
final class BatteryModel: NSObject, ObservableObject, NSWindowDelegate {
    @Published var records: [BatteryRecord] = []
    @Published var status = "batterySync.mac.waiting"
    @Published var paired = false
    @Published var pairingCode: String?
    @Published var pairingExpiry = Date.distantPast
    @Published var wirelessState = "waiting"
    private let publisher = SystemBatteryPublisher()
    private var wireless: WirelessServer?
    private var task: Task<Void, Never>?
    private var termination: NSObjectProtocol?
    private var pairingWindow: NSWindow?
    private var showInitialPairing = true
    private var lastUpdate: Date?
    private var lastUSB = Date.distantPast

    override init() {
        super.init()
        termination = NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification,
            object: nil, queue: .main) { [weak self] _ in MainActor.assumeIsolated { self?.stop() } }
        do {
            let server = try WirelessServer()
            wireless = server; paired = server.isPaired; showInitialPairing = !paired
            server.onSnapshot = { [weak self] snapshot in self?.publish(snapshot, wireless: true) }
            server.onState = { [weak self, weak server] state in
                guard let self, let server else { return }
                self.wirelessState = state; self.paired = server.isPaired
                self.status = "batterySync.wireless.status.\(state)"
                if state == "unpaired" { self.lastUpdate = nil; self.publisher.withdrawAll(); self.records = [] }
                if state == "unpaired" && self.showInitialPairing { self.showInitialPairing = false; self.openPairing() }
                if self.pairingWindow != nil && self.pairingCode == nil && !self.paired { self.renewPairing() }
            }
            server.onPaired = { [weak self] in self?.paired = true; self?.pairingCode = nil }
            try server.start()
        } catch { wirelessState = "unavailable"; status = "batterySync.wireless.status.unavailable" }
        start()
        if CommandLine.arguments.contains("--pair") { DispatchQueue.main.async { [weak self] in self?.openPairing() } }
    }
    func start() {
        wireless?.refresh(); lastUSB = .distantPast
        task?.cancel()
        task = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                if self.lastUpdate.map({ Date().timeIntervalSince($0) > 180 }) == true {
                    self.publisher.withdrawAll(); self.records = []; self.lastUpdate = nil
                }
                if self.wireless?.isConnected != true && USBReceiver.isDeviceConnected && Date().timeIntervalSince(self.lastUSB) >= 60 {
                    self.lastUSB = Date()
                    await self.refreshUSB()
                }
                do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
            }
        }
    }
    private func publish(_ snapshot: BatterySnapshot, wireless: Bool) {
        do {
            let updated = snapshot.records(phoneName: Catalog.text("batterySync.phone"), wearableName: Catalog.text("batterySync.wearable"))
            try publisher.update(updated)
            records = updated; lastUpdate = Date()
            status = wireless ? "batterySync.wireless.status.connected" : "batterySync.mac.connected"
        } catch { status = "batterySync.mac.systemUnavailable" }
    }
    private func refreshUSB() async {
        do {
            let (snapshot, computerSynced) = try await Task.detached {
                let receiver = try USBReceiver()
                let snapshot = try receiver.read()
                do { try receiver.sendComputerBatteries(ComputerBattery.readConnected()); return (snapshot, true) }
                catch { return (snapshot, false) }
            }.value
            guard !Task.isCancelled, wireless?.isConnected != true else { return }
            publish(snapshot, wireless: false)
            if !computerSynced { status = "batterySync.mac.partial" }
        } catch {
            guard !Task.isCancelled, wireless?.isConnected != true else { return }
            if lastUpdate == nil { publisher.withdrawAll(); records = [] }
            status = "batterySync.wireless.status.\(wirelessState)"
        }
    }
    func openPairing() {
        if let window = pairingWindow { window.makeKeyAndOrderFront(nil); NSApplication.shared.activate(ignoringOtherApps: true); return }
        renewPairing()
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 400, height: 520),
            styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = Catalog.text("batterySync.wireless.pair")
        window.contentView = NSHostingView(rootView: PairingView(model: self))
        window.isReleasedWhenClosed = false; window.delegate = self
        pairingWindow = window
        window.center(); window.makeKeyAndOrderFront(nil); NSApplication.shared.activate(ignoringOtherApps: true)
    }
    func renewPairing() {
        guard !paired else { pairingCode = nil; return }
        do {
            guard let result = try wireless?.pairingCode() else { throw WireError.notListening }
            pairingCode = result.code; pairingExpiry = result.expires
        } catch { pairingCode = nil }
    }
    func forgetPairing() {
        do { try wireless?.forget(); paired = false; pairingCode = nil; lastUpdate = nil; publisher.withdrawAll(); records = [] }
        catch { status = "common.operationFailed" }
    }
    func windowWillClose(_ notification: Notification) {
        wireless?.cancelPairing(); pairingCode = nil; pairingWindow = nil
    }
    func stop() { task?.cancel(); task = nil; wireless?.stop(); publisher.withdrawAll() }
}

private struct PairingView: View {
    @ObservedObject var model: BatteryModel
    @State private var image: NSImage?
    private func qr(_ text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8); filter.correctionLevel = "M"
        guard let image = filter.outputImage,
              let bitmap = CIContext().createCGImage(image.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
                  from: image.extent.applying(CGAffineTransform(scaleX: 8, y: 8))) else { return nil }
        return NSImage(cgImage: bitmap, size: NSSize(width: 256, height: 256))
    }
    var body: some View {
        VStack {
            Text(Catalog.text("batterySync.wireless.pair")).font(.title2)
            if model.paired {
                Image.englishSystemName("checkmark.circle").font(.largeTitle)
                Text(Catalog.text("batterySync.wireless.paired"))
            } else {
                Text(Catalog.text("batterySync.wireless.pairHint")).multilineTextAlignment(.center)
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    VStack {
                        if model.pairingCode != nil, context.date < model.pairingExpiry, let image {
                            Image(nsImage: image).interpolation(.none).resizable().scaledToFit().frame(width: 256, height: 256)
                            Text(Catalog.text("batterySync.wireless.expires")).font(.caption)
                        } else { Text(Catalog.text(model.pairingCode == nil ? "batterySync.wireless.status.unavailable" : "batterySync.wireless.expired")) }
                    }
                }
                Button(Catalog.text("batterySync.wireless.renew")) { model.renewPairing() }
            }
        }.padding().frame(maxWidth: .infinity, maxHeight: .infinity)
            .onAppear { image = model.pairingCode.flatMap(qr) }
            .onChange(of: model.pairingCode) { code in image = code.flatMap(qr) }
    }
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
            ForEach(model.records, id: \.id) { record in Text("\(record.name)  \(record.percent)%  \(Catalog.text(record.charging ? "batterySync.charging" : "batterySync.notCharging"))") }
            Divider()
            Button(Catalog.text("batterySync.wireless.pair")) { model.openPairing() }
            if model.paired { Button(Catalog.text("batterySync.wireless.forget")) { model.forgetPairing() } }
            Button(Catalog.text("batterySync.mac.refresh")) { model.start() }
            Button(Catalog.text("batterySync.mac.quit")) { model.stop(); NSApplication.shared.terminate(nil) }
        } label: {
            Label { Text(Catalog.text("batterySync.title")) } icon: { Image.englishSystemName("battery.100") }
        }
    }
}
