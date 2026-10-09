import assert from 'node:assert/strict';
import test from 'node:test';
import { initialSnapshot, validBatteryLevel, visibleComputerDevices, visibleWearableLevel } from './battery-state.ts';

test('断线或授权失效时不显示缓存电量', () => {
  for (const status of ['deviceDisconnected', 'permissionDenied', 'timeout', 'signatureRejected', 'moduleRequired'] as const) {
    assert.equal(visibleWearableLevel({ ...initialSnapshot, status, wearableLevel: 78 }), null);
  }
});

test('电脑配件断开后隐藏过期电量，保留有效的真实零电量', () => {
  const snapshot = { ...initialSnapshot, computerUpdatedAt: 1_000,
    computerDevices: [{ name: 'Trackpad', category: 'trackpad', level: 0, charging: false }] };
  assert.equal(visibleComputerDevices(snapshot, 181_000).length, 1);
  assert.equal(visibleComputerDevices(snapshot, 181_001).length, 0);
  assert.equal(visibleComputerDevices({ ...snapshot, computerUpdatedAt: 40_000 }, 1_000).length, 0);
  assert.equal(visibleComputerDevices({ ...snapshot, computerUpdatedAt: null }, 1_000).length, 0);
  assert.equal(visibleComputerDevices({ ...snapshot, computerDevices: [{ ...snapshot.computerDevices[0]!, level: 101 }] }, 1_000).length, 0);
  for (const level of [-1, 101, 0.5, NaN, Infinity]) assert.equal(validBatteryLevel(level), null);
});

test('拒绝未知、超范围与非整数电量，保留真实零电量', () => {
  for (const wearableLevel of [null, -1, 101, 0.5, NaN]) {
    assert.equal(visibleWearableLevel({ ...initialSnapshot, status: 'ready', wearableLevel }), null);
  }
  for (const wearableLevel of [0, 78, 100]) {
    assert.equal(visibleWearableLevel({ ...initialSnapshot, status: 'ready', wearableLevel }), wearableLevel);
  }
});
