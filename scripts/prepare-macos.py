from pathlib import Path
import shutil

# 从共享目录生成原生客户端语言资源。
project = Path(__file__).resolve().parent.parent
resources = project / 'macos/Sources/BatMate/Resources'
resources.mkdir(parents=True, exist_ok=True)
for language in ['zh-CN', 'en']:
    shutil.copyfile(project / 'locales' / f'{language}.json', resources / f'{language}.json')
