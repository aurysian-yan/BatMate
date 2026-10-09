from pathlib import Path
from urllib.request import urlopen
from zipfile import ZipFile
from io import BytesIO
from hashlib import sha256
from xml.sax.saxutils import escape
import json

# 官方 SDK 固定来源与校验值，二进制和生成的语言资源不入库。
project = Path(__file__).resolve().parent.parent
repository = project
android = project / "modules/battery-native/android"
sdk = android / "libs/xms-wearable-lib_1.4_release.aar"
expected_sdk = "9c40fd1c5409bb948523d503af71e2978ae522c35636afe7d64f474c0f6bc195"

if not sdk.exists() or sha256(sdk.read_bytes()).hexdigest() != expected_sdk:
    archive = urlopen("https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip", timeout=60).read()
    if sha256(archive).hexdigest() != "8e3d74eebda558e2bef45e32f0965c5b77ef939a06cdc55136929f9427a95ca7":
        raise RuntimeError("官方示例校验失败")
    with ZipFile(BytesIO(archive)) as zipped:
        payload = zipped.read("interconnect_dev_test_demo/libs/xms-wearable-lib_1.4_release.aar")
    if sha256(payload).hexdigest() != expected_sdk:
        raise RuntimeError("SDK 校验失败")
    sdk.parent.mkdir(parents=True, exist_ok=True)
    sdk.write_bytes(payload)

# 框架接口仅供编译使用，运行时由 LSPosed 提供。
xposed = android / "libs/libxposed-api-102.0.0.aar"
expected_xposed = "423484a6e1807e7a423c4b88fcd8176d104318259d91791877fed88fe91479d0"
if not xposed.exists() or sha256(xposed.read_bytes()).hexdigest() != expected_xposed:
    payload = urlopen("https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar", timeout=60).read()
    if sha256(payload).hexdigest() != expected_xposed:
        raise RuntimeError("Xposed API 校验失败")
    xposed.parent.mkdir(parents=True, exist_ok=True)
    xposed.write_bytes(payload)

for language, qualifier in [("en", "values"), ("zh-CN", "values-zh-rCN")]:
    catalog = json.loads((repository / "locales" / f"{language}.json").read_text())["batterySync"]
    values = {
        "battery_app_name": catalog["title"],
        "battery_module_description": catalog["moduleDescription"],
        "battery_notification_channel": catalog["notificationChannel"],
        "battery_notification_title": catalog["title"],
        "battery_notification_body": catalog["notificationBody"],
    }
    resource = android / "src/main/res" / qualifier / "strings.xml"
    resource.parent.mkdir(parents=True, exist_ok=True)
    elements = [f'  <string name="{key}">{escape(value).replace(chr(39), chr(92) + chr(39))}</string>' for key, value in values.items()]
    resource.write_text('<resources>\n' + '\n'.join(elements) + '\n</resources>\n')

print("Android SDK 与共享语言资源已准备")
