import { NativeModule, requireNativeModule } from 'expo-modules-core';
import type { BatterySnapshot } from './battery-state';

declare class BatteryNativeModule extends NativeModule<{
  onState: (snapshot: BatterySnapshot) => void;
}> {
  snapshot(): BatterySnapshot;
  refresh(): void;
  authorize(): void;
  startBackground(): void;
  stopBackground(): void;
  openHost(): void;
  openSettings(): void;
  diagnostics(): string;
  requestBackgroundPermissions(): Promise<{ granted: boolean }>;
}

export default requireNativeModule<BatteryNativeModule>('BatteryNative');
