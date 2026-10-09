import { requireNativeViewManager } from 'expo-modules-core';
import type { ViewProps } from 'react-native';
import type { ComputerBattery } from './battery-state';

export type DashboardAction = 'refresh' | 'authorize' | 'startBackground' | 'stopBackground'
  | 'openHost' | 'openSettings' | 'share' | 'dismiss';

export interface DashboardModel {
  phoneName: string;
  phoneLevel: number | null;
  phoneStatus: string;
  phoneCharging: boolean;
  wearableName: string;
  wearableLevel: number | null;
  wearableStatus: string;
  wearableCharging: boolean;
  lastRead: string;
  backgroundRunning: boolean;
  controlsEnabled: boolean;
  readEnabled: boolean;
  reading: boolean;
  computerConnected: boolean;
  computerDevices: ComputerBattery[];
  noticeTitle: string;
  notice: string;
}

interface DashboardProps extends ViewProps {
  model: DashboardModel;
  labels: Record<string, string>;
  onAction: (event: { nativeEvent: { action: DashboardAction } }) => void;
}

export default requireNativeViewManager<DashboardProps>('BatteryNative', 'BatteryDashboardView');
