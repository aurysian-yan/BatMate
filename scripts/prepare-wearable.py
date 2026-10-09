from pathlib import Path
import json
import os
import struct
import subprocess
import tempfile
import zlib

# 共用手机应用身份和语言目录，签名只保留在忽略目录。
project = Path(__file__).resolve().parent.parent
repository = project
wearable = project / "wearable"
keystore = project / "android/app/debug.keystore"
if not keystore.exists():
    raise RuntimeError("请先构建 Android 安装包以准备配套签名")

package = "com.folio.batterysync.probe"
version = json.loads((project / "package.json").read_text())["version"]
for language in ["zh-CN", "en"]:
    catalog = json.loads((repository / "locales" / f"{language}.json").read_text())
    destination = wearable / "src/i18n" / f"{language}.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps({"batterySync": catalog["batterySync"]}, ensure_ascii=False, indent=2) + "\n")
    if language == "zh-CN":
        (destination.parent / "defaults.json").write_text(destination.read_text())
        name = catalog["batterySync"]["title"]

manifest = {
    "package": package, "name": name, "versionName": version, "versionCode": 2,
    "minPlatformVersion": 1000, "icon": "/common/icon.png", "deviceTypeList": ["watch"],
    "features": [{"name": feature} for feature in
                 ["system.battery", "system.interconnect", "system.storage"]],
    "config": {"designWidth": "device-width"},
    "router": {"entry": "pages/index", "pages": {"pages/index": {"component": "index"}}},
}
(wearable / "src/manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")

# 使用简洁电池图标，透明底色适配设备桌面。
def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

rows = []
for y in range(64):
    row = bytearray([0])
    for x in range(64):
        shell = 10 <= x < 50 and 18 <= y < 46
        hollow = 14 <= x < 46 and 22 <= y < 42
        terminal = 50 <= x < 55 and 26 <= y < 38
        fill = 18 <= x < 35 and 26 <= y < 38
        row.extend([255, 255, 255, 255 if (shell and not hollow) or terminal or fill else 0])
    rows.append(bytes(row))
icon = wearable / "src/common/icon.png"
icon.parent.mkdir(parents=True, exist_ok=True)
icon.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b""))

java_home = os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home")
(project / ".build").mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(dir=project / ".build") as temporary:
    p12 = Path(temporary) / "signature.p12"
    subprocess.run([str(Path(java_home) / "bin/keytool"), "-importkeystore", "-srckeystore", str(keystore),
                    "-srcstorepass", "android", "-srcalias", "androiddebugkey", "-srckeypass", "android",
                    "-destkeystore", str(p12), "-deststoretype", "PKCS12", "-deststorepass", "android",
                    "-destkeypass", "android", "-noprompt"], check=True, capture_output=True)
    pem = subprocess.run(["openssl", "pkcs12", "-nodes", "-in", str(p12), "-passin", "pass:android"],
                         check=True, capture_output=True).stdout.decode()
    def extract(kind):
        start = pem.index(f"-----BEGIN {kind}-----")
        end = pem.index(f"-----END {kind}-----", start) + len(f"-----END {kind}-----")
        return pem[start:end] + "\n"
    for mode in ["debug", "release"]:
        destination = wearable / "sign" / mode
        destination.mkdir(parents=True, exist_ok=True)
        for filename, kind in [("private.pem", "PRIVATE KEY"), ("certificate.pem", "CERTIFICATE")]:
            target = destination / filename
            target.write_text(extract(kind))
            target.chmod(0o600)

print("手环应用身份、配套签名与共享语言资源已准备")
