import Foundation
import Network
import Security
import Darwin

// TLS 服务只提供配对与电量交换，不提供远程操作接口。
public final class WirelessServer {
    private let queue = DispatchQueue(label: "app.batmate.wireless")
    private let identity: WirelessIdentity
    private let authority: PairingAuthority
    private var listener: NWListener?
    private var port: UInt16?
    private var sessions: [UUID: Session] = [:]
    private var active: UUID?
    private var heartbeat: DispatchSourceTimer?
    public var onSnapshot: ((BatterySnapshot) -> Void)?
    public var onState: ((String) -> Void)?
    public var onPaired: (() -> Void)?

    public init() throws {
        identity = try WirelessIdentity.load()
        let peer = try WirelessKeychain.read("phone").map { try JSONDecoder().decode(PairedPhone.self, from: $0) }
        authority = PairingAuthority(peer: peer)
    }
    public var isPaired: Bool { queue.sync { authority.peer != nil } }
    public var isConnected: Bool { queue.sync { active != nil } }

    public func start() throws {
        let tls = NWProtocolTLS.Options()
        guard let local = sec_identity_create(identity.identity) else { throw WireError.identity }
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, local)
        let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        parameters.includePeerToPeer = false
        let listener = try NWListener(using: parameters, on: .any)
        listener.service = NWListener.Service(name: "BatMate-\(identity.id.prefix(8))", type: "_batmate._tcp",
            txtRecord: NWTXTRecord(["id": identity.id, "v": "1"]))
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self else { return }
            switch state {
            case .ready: self.port = listener?.port?.rawValue; self.state(self.authority.peer == nil ? "unpaired" : "waiting")
            case .failed: self.port = nil; self.state("unavailable")
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        self.listener = listener
        listener.start(queue: queue)
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 60, repeating: 60)
        timer.setEventHandler { [weak self] in self?.sendComputerBatteries() }
        heartbeat = timer; timer.resume()
    }

    public func pairingCode() throws -> (code: String, expires: Date) {
        try queue.sync {
            guard let port else { throw WireError.notListening }
            guard authority.peer == nil else { throw WireError.unauthorized }
            let (token, expires) = try authority.begin()
            let object: [String: Any] = ["v": 1, "id": identity.id, "port": port,
                "addresses": Self.addresses(), "fingerprint": identity.fingerprint, "token": token,
                "expires": expires.timeIntervalSince1970 * 1000]
            let data = try JSONSerialization.data(withJSONObject: object)
            let encoded = data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
            return ("batmate://pair?data=\(encoded)", expires)
        }
    }
    public func cancelPairing() { queue.async { self.authority.cancel() } }
    public func forget() throws {
        try queue.sync {
            try WirelessKeychain.delete("phone")
            authority.forget()
            for session in Array(sessions.values) { close(session) }
            state("unpaired")
        }
    }
    public func refresh() {
        queue.async {
            if let id = self.active, let session = self.sessions[id] { self.send(["type": "refresh"], to: session) }
            self.sendComputerBatteries()
        }
    }
    public func stop() {
        queue.sync {
            heartbeat?.cancel(); heartbeat = nil
            listener?.cancel(); listener = nil; port = nil
            for session in Array(sessions.values) { close(session) }
            authority.cancel()
        }
    }

    private final class Session {
        let id = UUID()
        let connection: NWConnection
        var authenticated = false
        var deadline: DispatchWorkItem?
        init(_ connection: NWConnection) { self.connection = connection }
    }
    private func accept(_ connection: NWConnection) {
        guard sessions.count < 8 else { connection.cancel(); return }
        let session = Session(connection)
        sessions[session.id] = session
        connection.stateUpdateHandler = { [weak self, weak session] state in
            guard let self, let session else { return }
            switch state {
            case .ready: self.receiveHeader(session)
            case .failed, .cancelled: self.close(session)
            default: break
            }
        }
        armTimeout(session, seconds: 10)
        connection.start(queue: queue)
    }
    private func armTimeout(_ session: Session, seconds: Int) {
        session.deadline?.cancel()
        let task = DispatchWorkItem { [weak self, weak session] in
            if let self, let session { self.close(session) }
        }
        session.deadline = task
        queue.asyncAfter(deadline: .now() + .seconds(seconds), execute: task)
    }
    private func receiveHeader(_ session: Session) {
        session.connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self, weak session] data, _, done, error in
            guard let self, let session, self.sessions[session.id] != nil else { return }
            guard error == nil, !done, let data, let size = try? WireProtocol.size(data) else { self.close(session); return }
            self.receiveBody(session, size: size)
        }
    }
    private func receiveBody(_ session: Session, size: Int) {
        session.connection.receive(minimumIncompleteLength: size, maximumLength: size) { [weak self, weak session] data, _, done, error in
            guard let self, let session, self.sessions[session.id] != nil else { return }
            do {
                guard error == nil, !done, let data, data.count == size else { throw WireError.invalidMessage }
                try self.handle(WireProtocol.decode(data), session: session)
                guard self.sessions[session.id] != nil else { return }
                self.armTimeout(session, seconds: 90)
                self.receiveHeader(session)
            } catch {
                if session.authenticated { self.close(session) }
                else if let data = try? WireProtocol.frame(["type": "rejected"]) {
                    session.connection.send(content: data, completion: .contentProcessed { _ in self.close(session) })
                } else { self.close(session) }
            }
        }
    }
    private func handle(_ message: [String: Any], session: Session) throws {
        let type = message["type"] as? String
        if !session.authenticated {
            guard let token = message["token"] as? String, token.count <= 128,
                  let id = message["phoneID"] as? String else { throw WireError.unauthorized }
            if type == "pair" {
                let phone = try authority.claim(token: token, id: id)
                try WirelessKeychain.write(JSONEncoder().encode(phone), account: "phone")
                authority.commit(phone)
                send(["type": "paired", "token": phone.token], to: session)
                DispatchQueue.main.async { [weak self] in self?.onPaired?() }
            } else {
                guard type == "auth", authority.authenticate(token: token, id: id) else { throw WireError.unauthorized }
                send(["type": "ready"], to: session)
            }
            if let old = active, let previous = sessions[old] { close(previous) }
            session.authenticated = true; active = session.id
            state("connected")
            sendComputerBatteries()
            return
        }
        if type == "unpair" {
            try WirelessKeychain.delete("phone"); authority.forget(); close(session); state("unpaired")
            return
        }
        guard type == "snapshot" else { throw WireError.invalidMessage }
        let snapshot = try WireProtocol.snapshot(message["snapshot"])
        guard snapshot.status != "reading" else { return }
        DispatchQueue.main.async { [weak self] in self?.onSnapshot?(snapshot) }
        sendComputerBatteries()
    }
    private func sendComputerBatteries() {
        guard let active, let session = sessions[active] else { return }
        do {
            let devices = try ComputerBattery.readConnected()
            let object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(devices))
            send(["type": "computerDevices", "devices": object], to: session)
        } catch { state("partial") }
    }
    private func send(_ object: [String: Any], to session: Session) {
        guard let data = try? WireProtocol.frame(object) else { close(session); return }
        session.connection.send(content: data, completion: .contentProcessed { [weak self, weak session] error in
            if error != nil, let self, let session { self.close(session) }
        })
    }
    private func close(_ session: Session) {
        guard sessions.removeValue(forKey: session.id) != nil else { return }
        session.deadline?.cancel()
        session.connection.cancel()
        if active == session.id { active = nil; state("reconnecting") }
    }
    private func state(_ value: String) { DispatchQueue.main.async { [weak self] in self?.onState?(value) } }
    private static func addresses() -> [String] {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return [] }
        defer { freeifaddrs(list) }
        var result: [String] = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let item = cursor {
            defer { cursor = item.pointee.ifa_next }
            guard String(cString: item.pointee.ifa_name).hasPrefix("en"), let address = item.pointee.ifa_addr,
                  address.pointee.sa_family == AF_INET || address.pointee.sa_family == AF_INET6 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let value = String(cString: host)
                if !value.hasPrefix("fe80:") { result.append(value) }
            }
        }
        return result
    }
}
