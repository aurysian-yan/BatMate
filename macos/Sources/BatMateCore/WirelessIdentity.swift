import Foundation
import Security
import CryptoKit
import X509

// 身份、证书及配对凭据保存在钥匙串，不进入构建目录。
enum WirelessKeychain {
    private static let service = "app.batmate.wireless"
    static func read(_ account: String) throws -> Data? {
        var value: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass: kSecClassGenericPassword, kSecAttrService: service,
            kSecAttrAccount: account, kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne] as CFDictionary, &value)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = value as? Data else { throw WireError.identity }
        return data
    }
    static func write(_ data: Data, account: String) throws {
        let query = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account] as CFDictionary
        var status = SecItemUpdate(query, [kSecValueData: data] as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd([kSecClass: kSecClassGenericPassword, kSecAttrService: service,
                kSecAttrAccount: account, kSecValueData: data] as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw WireError.identity }
    }
    static func delete(_ account: String) throws {
        let status = SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service,
            kSecAttrAccount: account] as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw WireError.identity }
    }
}

struct WirelessIdentity {
    let identity: SecIdentity
    let fingerprint: String
    let id: String

    static func load() throws -> Self {
        let tag = Data("app.batmate.wireless.identity".utf8)
        var item: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass: kSecClassKey, kSecAttrApplicationTag: tag,
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom, kSecReturnRef: true] as CFDictionary, &item)
        let key: SecKey
        if status == errSecSuccess { key = item as! SecKey }
        else if status == errSecItemNotFound {
            var error: Unmanaged<CFError>?
            guard let created = SecKeyCreateRandomKey([kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeySizeInBits: 256, kSecPrivateKeyAttrs: [kSecAttrIsPermanent: true,
                    kSecAttrApplicationTag: tag, kSecAttrLabel: "BatMate Local TLS"]] as CFDictionary, &error) else { throw WireError.identity }
            key = created
        } else { throw WireError.identity }
        let certData: Data
        if let existing = try WirelessKeychain.read("certificate") { certData = existing }
        else {
            let signing = try Certificate.PrivateKey(key)
            let name = try DistinguishedName { CommonName("BatMate Local Sync") }
            let certificate = try Certificate(version: .v3, serialNumber: .init(), publicKey: signing.publicKey,
                notValidBefore: Date().addingTimeInterval(-86400), notValidAfter: Date().addingTimeInterval(315_360_000),
                issuer: name, subject: name, signatureAlgorithm: .ecdsaWithSHA256,
                extensions: Certificate.Extensions {
                    Critical(BasicConstraints.notCertificateAuthority)
                    Critical(KeyUsage(digitalSignature: true))
                    try ExtendedKeyUsage([.serverAuth])
                }, issuerPrivateKey: signing)
            certData = Data(try certificate.serializeAsPEM().derBytes)
            try WirelessKeychain.write(certData, account: "certificate")
        }
        guard let cert = SecCertificateCreateWithData(nil, certData as CFData) else { throw WireError.identity }
        let added = SecItemAdd([kSecClass: kSecClassCertificate, kSecValueRef: cert,
            kSecAttrLabel: "BatMate Local TLS"] as CFDictionary, nil)
        guard added == errSecSuccess || added == errSecDuplicateItem else { throw WireError.identity }
        var identity: SecIdentity?
        guard SecIdentityCreateWithCertificate(nil, cert, &identity) == errSecSuccess, let identity else { throw WireError.identity }
        let id: String
        if let data = try WirelessKeychain.read("serverID"), let saved = String(data: data, encoding: .utf8) { id = saved }
        else { id = UUID().uuidString; try WirelessKeychain.write(Data(id.utf8), account: "serverID") }
        return Self(identity: identity, fingerprint: SHA256.hash(data: certData).map { String(format: "%02x", $0) }.joined(), id: id)
    }
}
