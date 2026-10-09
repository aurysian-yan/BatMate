import assert from 'node:assert/strict';
import test from 'node:test';
import { initialSnapshot, visibleWearableLevel } from './battery-state.ts';

test('断线或授权失效时不显示缓存电量', () => {
  for (const status of ['deviceDisconnected', 'permissionDenied', 'timeout', 'signatureRejected', 'moduleRequired'] as const) {
    assert.equal(visibleWearableLevel({ ...initialSnapshot, status, wearableLevel: 78 }), null);
  }
});

test('拒绝未知、超范围与非整数电量，保留真实零电量', () => {
  for (const wearableLevel of [null, -1, 101, 0.5, NaN]) {
    assert.equal(visibleWearableLevel({ ...initialSnapshot, status: 'ready', wearableLevel }), null);
  }
  for (const wearableLevel of [0, 78, 100]) {
    assert.equal(visibleWearableLevel({ ...initialSnapshot, status: 'ready', wearableLevel }), wearableLevel);
  }
});
