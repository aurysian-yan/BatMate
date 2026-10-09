export type BatteryStatus =
  | 'idle' | 'reading' | 'ready' | 'appMissing' | 'deviceDisconnected'
  | 'permissionRequired' | 'permissionDenied' | 'signatureRejected'
  | 'moduleRequired' | 'timeout' | 'unavailable' | 'serviceDisconnected' | 'multipleDevices' | 'companionMissing';

export interface BatterySnapshot {
  status: BatteryStatus;
  phoneLevel: number | null;
  phoneName: string | null;
  phoneCharging: boolean | null;
  wearableName: string | null;
  wearableLevel: number | null;
  wearableCharging: boolean | null;
  updatedAt: number | null;
  attemptedAt: number | null;
  backgroundRunning: boolean;
  errorCode: string | null;
  hostPackage: string | null;
  hostVersion: string | null;
  sdkApiLevel: number | null;
  wearableAppInstalled: boolean | null;
  companionCheckError: string | null;
  queryStage: string | null;
  computerDevices: ComputerBattery[];
  computerUpdatedAt: number | null;
}

export interface ComputerBattery {
  name: string;
  category: string;
  level: number;
  charging: boolean | null;
}

export const initialSnapshot: BatterySnapshot = {
  status: 'idle', phoneName: null, phoneLevel: null, phoneCharging: null, wearableName: null,
  wearableLevel: null, wearableCharging: null, updatedAt: null, attemptedAt: null,
  backgroundRunning: false, errorCode: null, hostPackage: null,
  hostVersion: null, sdkApiLevel: null, wearableAppInstalled: null,
  companionCheckError: null, queryStage: null,
  computerDevices: [], computerUpdatedAt: null,
};

export function validBatteryLevel(value: number | null): number | null {
  return value !== null && Number.isInteger(value) && value >= 0 && value <= 100 ? value : null;
}

export function visibleComputerDevices(snapshot: BatterySnapshot, now = Date.now()): ComputerBattery[] {
  const updated = snapshot.computerUpdatedAt;
  if (updated === null || !Number.isFinite(updated) || now - updated > 180_000 || updated > now + 30_000) return [];
  return (snapshot.computerDevices ?? []).filter(device => typeof device.name === 'string' && device.name.trim()
    && validBatteryLevel(device.level) !== null);
}

export function visibleWearableLevel(snapshot: BatterySnapshot): number | null {
  const value = snapshot.wearableLevel;
  return snapshot.status === 'ready' ? validBatteryLevel(value) : null;
}
