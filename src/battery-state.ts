export type BatteryStatus =
  | 'idle' | 'reading' | 'ready' | 'appMissing' | 'deviceDisconnected'
  | 'permissionRequired' | 'permissionDenied' | 'signatureRejected'
  | 'moduleRequired' | 'timeout' | 'unavailable' | 'serviceDisconnected' | 'multipleDevices' | 'companionMissing';

export interface BatterySnapshot {
  status: BatteryStatus;
  phoneLevel: number | null;
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
}

export const initialSnapshot: BatterySnapshot = {
  status: 'idle', phoneLevel: null, phoneCharging: null, wearableName: null,
  wearableLevel: null, wearableCharging: null, updatedAt: null, attemptedAt: null,
  backgroundRunning: false, errorCode: null, hostPackage: null,
  hostVersion: null, sdkApiLevel: null, wearableAppInstalled: null,
  companionCheckError: null, queryStage: null,
};

export function visibleWearableLevel(snapshot: BatterySnapshot): number | null {
  const value = snapshot.wearableLevel;
  return snapshot.status === 'ready' && value !== null && Number.isInteger(value)
    && value >= 0 && value <= 100 ? value : null;
}
