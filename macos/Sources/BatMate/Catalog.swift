import Foundation

// 原生界面与手机端使用同一份语言目录。
enum Catalog {
    static let values: [String: Any] = {
        let language = Locale.preferredLanguages.first?.hasPrefix("zh") == true ? "zh-CN" : "en"
        let url = Bundle.main.url(forResource: language, withExtension: "json") ??
            Bundle.module.url(forResource: language, withExtension: "json", subdirectory: "Resources")
        guard let url, let data = try? Data(contentsOf: url),
              let result = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return result
    }()
    static func text(_ key: String) -> String {
        var value: Any = values
        for part in key.split(separator: ".") {
            guard let next = (value as? [String: Any])?[String(part)] else { return "" }
            value = next
        }
        return value as? String ?? ""
    }
}
