import type { ExpoConfig } from 'expo/config';
import catalog from './locales/zh-CN.json';

const config: ExpoConfig = {
  name: catalog.batterySync.title,
  slug: 'batmate',
  version: '0.1.0',
  platforms: ['android'],
  userInterfaceStyle: 'automatic',
  android: {
    package: 'com.folio.batterysync.probe',
    permissions: [],
    blockedPermissions: [
      'android.permission.READ_EXTERNAL_STORAGE',
      'android.permission.WRITE_EXTERNAL_STORAGE',
      'android.permission.SYSTEM_ALERT_WINDOW',
      'android.permission.VIBRATE',
    ],
    predictiveBackGestureEnabled: true,
  },
  plugins: [
    'expo-localization',
    ['expo-build-properties', {
      android: { minSdkVersion: 28, compileSdkVersion: 37, targetSdkVersion: 36, buildArchs: ['arm64-v8a'] },
    }],
  ],
};

export default config;
