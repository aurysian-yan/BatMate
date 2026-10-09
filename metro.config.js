// 允许独立应用读取共享语言目录。
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const projectRoot = path.dirname(fileURLToPath(import.meta.url));
const config = require('expo/metro-config').getDefaultConfig(projectRoot);
config.watchFolders = [...(config.watchFolders ?? []), path.resolve(projectRoot, 'locales')];

export default config;
