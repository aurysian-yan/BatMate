import Foundation
import Darwin

// 发现包只包含公开的服务信息，认证与电量仍通过 TLS 交换。
enum DiscoveryMessage {
    static let port: UInt16 = 53318
    static let group = "224.0.0.168"
    static func isQuery(_ data: Data, id: String) -> Bool {
        guard data.count <= 1024, let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = value["v"] as? NSNumber, CFGetTypeID(version) != CFBooleanGetTypeID() else { return false }
        return version.intValue == 1 && version.doubleValue == 1 && value["app"] as? String == "batmate"
            && value["type"] as? String == "discover" && value["id"] as? String == id
    }
    static func announcement(id: String, fingerprint: String, port: UInt16) throws -> Data {
        try JSONSerialization.data(withJSONObject: ["app": "batmate", "v": 1, "type": "announce",
            "id": id, "fingerprint": fingerprint, "port": port])
    }
}

final class LANDiscovery {
    private var source: DispatchSourceRead?
    private let descriptor: Int32
    private var lastResponse = Date.distantPast

    init(queue: DispatchQueue, id: String, fingerprint: String, port: UInt16) throws {
        let response = try DiscoveryMessage.announcement(id: id, fingerprint: fingerprint, port: port)
        descriptor = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard descriptor >= 0 else { throw WireError.notListening }
        var reuse: Int32 = 1
        setsockopt(descriptor, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout.size(ofValue: reuse)))
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = DiscoveryMessage.port.bigEndian
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(descriptor, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard bound == 0 else { Darwin.close(descriptor); throw WireError.notListening }
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        if getifaddrs(&interfaces) == 0 {
            var item = interfaces
            while let current = item {
                defer { item = current.pointee.ifa_next }
                guard String(cString: current.pointee.ifa_name).hasPrefix("en"),
                      current.pointee.ifa_flags & UInt32(IFF_UP) != 0,
                      let local = current.pointee.ifa_addr, local.pointee.sa_family == AF_INET else { continue }
                var membership = ip_mreq()
                membership.imr_multiaddr.s_addr = inet_addr(DiscoveryMessage.group)
                membership.imr_interface = UnsafeRawPointer(local).assumingMemoryBound(to: sockaddr_in.self).pointee.sin_addr
                setsockopt(descriptor, IPPROTO_IP, IP_ADD_MEMBERSHIP, &membership, socklen_t(MemoryLayout.size(ofValue: membership)))
            }
            freeifaddrs(interfaces)
        }
        _ = fcntl(descriptor, F_SETFL, O_NONBLOCK)
        let reader = DispatchSource.makeReadSource(fileDescriptor: descriptor, queue: queue)
        reader.setEventHandler { [weak self] in self?.receive(id: id, response: response) }
        let fd = descriptor
        reader.setCancelHandler { Darwin.close(fd) }
        source = reader
        reader.resume()
    }
    func stop() { source?.cancel(); source = nil }
    deinit { stop() }

    private func receive(id: String, response: Data) {
        var buffer = [UInt8](repeating: 0, count: 1025)
        var remote = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let count = withUnsafeMutablePointer(to: &remote) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(descriptor, &buffer, buffer.count, 0, $0, &length) }
        }
        guard count > 0, DiscoveryMessage.isQuery(Data(buffer.prefix(count)), id: id),
              Date().timeIntervalSince(lastResponse) >= 0.1 else { return }
        lastResponse = Date()
        response.withUnsafeBytes { bytes in
            withUnsafePointer(to: &remote) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { _ = sendto(descriptor, bytes.baseAddress, bytes.count, 0, $0, length) }
            }
        }
    }
}
