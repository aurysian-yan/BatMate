from pathlib import Path
from zipfile import ZipFile
import json
import hashlib
import os
import shutil
import subprocess
import sys

# 构建后检查包名与实际内嵌证书，输出统一放入安装包目录。
project = Path(__file__).resolve().parent.parent
wearable = project / "wearable"
subprocess.run([sys.executable, str(project / "scripts/prepare-wearable.py")], check=True)
subprocess.run(["aiot", "release"], cwd=wearable, check=True)
manifest = json.loads((wearable / "src/manifest.json").read_text())
version = manifest["versionName"]
rpk = wearable / "dist" / f'{manifest["package"]}.release.{version}.rpk'
certificate = subprocess.check_output(["openssl", "x509", "-in",
                                       str(wearable / "sign/release/certificate.pem"), "-outform", "DER"])
android_sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
apk = project / ".build" / f"battery-sync-{version}.apk"
apk_signer = android_sdk / "build-tools/36.0.0/apksigner"
signers = subprocess.check_output([str(apk_signer), "verify", "--print-certs", str(apk)], text=True)
digest = hashlib.sha256(certificate).hexdigest()
if f"Signer #1 certificate SHA-256 digest: {digest}" not in signers:
    raise RuntimeError("手环证书与已构建的手机安装包不一致")
with ZipFile(rpk) as archive:
    packaged = json.loads(archive.read("manifest.json"))
    if packaged["package"] != manifest["package"] or certificate not in archive.read("META-INF/CERT"):
        raise RuntimeError("手环安装包的身份或配套证书校验失败")
output = project / ".build" / f"battery-sync-wearable-{version}.rpk"
shutil.copyfile(rpk, output)
print(output)
