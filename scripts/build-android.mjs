// 生成独立安装包，结束后停止本次构建的 Gradle 后台进程。
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const android = path.join(project, 'android');
const output = path.join(project, '.build');
const gradle = path.join(android, 'gradlew');
const env = { ...process.env, CI: '1' };

function run(command, args, cwd = project) {
  const result = spawnSync(command, args, { cwd, env, stdio: 'inherit' });
  if (result.status !== 0) throw new Error(`${command} 执行失败`);
}

let built = false;
try {
  run('pnpm', ['prebuild']);
  run(gradle, [':battery-native:testReleaseUnitTest', ':app:assembleRelease', '--no-daemon', '-PreactNativeArchitectures=arm64-v8a'], android);
  mkdirSync(output, { recursive: true });
  const { version } = JSON.parse(readFileSync(path.join(project, 'package.json'), 'utf8'));
  const apk = path.join(output, `battery-sync-${version}.apk`);
  copyFileSync(path.join(android, 'app/build/outputs/apk/release/app-release.apk'), apk);
  console.log(apk);
  built = true;
} finally {
  spawnSync(gradle, ['--stop'], { cwd: android, env, stdio: 'inherit' });
  if (!built) process.exitCode = 1;
}
