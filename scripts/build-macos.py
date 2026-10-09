from pathlib import Path
import plistlib
import json
import shutil
import subprocess
import sys

# 将原生可执行文件和语言资源封装为独立应用。
project = Path(__file__).resolve().parent.parent
subprocess.run([sys.executable, str(project / 'scripts/prepare-macos.py')], check=True)
subprocess.run(['swift', 'build', '-c', 'release'], cwd=project / 'macos', check=True)
binary_dir = Path(subprocess.check_output(['swift', 'build', '-c', 'release', '--show-bin-path'], cwd=project / 'macos', text=True).strip())
app = project / '.build/BatMate.app'
if app.exists():
    shutil.rmtree(app)
macos = app / 'Contents/MacOS'
resources = app / 'Contents/Resources'
macos.mkdir(parents=True)
resources.mkdir()
shutil.copy2(binary_dir / 'BatMate', macos / 'BatMate')
for bundle in binary_dir.glob('*.bundle'):
    shutil.copytree(bundle, resources / bundle.name)
for name in ['zh-CN', 'en']:
    shutil.copyfile(project / 'locales' / f'{name}.json', resources / f'{name}.json')
info = {
    'CFBundleName': 'BatMate', 'CFBundleDisplayName': '电伴', 'CFBundleIdentifier': 'app.batmate.mac',
    'CFBundleExecutable': 'BatMate', 'CFBundlePackageType': 'APPL', 'CFBundleShortVersionString': '0.1.0',
    'CFBundleVersion': '1', 'LSMinimumSystemVersion': '13.0', 'LSUIElement': True,
    'NSLocalNetworkUsageDescription': json.loads((project / 'locales/zh-CN.json').read_text())['batterySync']['wireless']['localNetworkHint'],
    'NSBonjourServices': ['_batmate._tcp'],
    'NSHighResolutionCapable': True, 'CFBundleDevelopmentRegion': 'en',
}
for language, folder in [('zh-CN', 'zh-Hans.lproj'), ('en', 'en.lproj')]:
    localized = resources / folder
    localized.mkdir()
    description = json.loads((project / 'locales' / f'{language}.json').read_text())['batterySync']['wireless']['localNetworkHint']
    (localized / 'InfoPlist.strings').write_text('\"NSLocalNetworkUsageDescription\" = ' + json.dumps(description, ensure_ascii=False) + ';\n')
(app / 'Contents/Info.plist').write_bytes(plistlib.dumps(info))
subprocess.run(['codesign', '--force', '--deep', '--sign', '-', str(app)], check=True)
print(app)
