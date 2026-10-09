import { useEffect, useRef, useState } from 'react';
import { AppState, Share, StatusBar, StyleSheet, useColorScheme } from 'react-native';
import { useTranslation } from 'react-i18next';
import BatteryNative from './battery-native';
import BatteryDashboard, { type DashboardAction } from './BatteryDashboard';
import { validBatteryLevel, visibleComputerDevices, visibleWearableLevel } from './battery-state';

export default function App() {
  const { t, i18n } = useTranslation();
  const dark = useColorScheme() === 'dark';
  const [snapshot, setSnapshot] = useState(() => BatteryNative.snapshot());
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(Date.now);
  const [notice, setNotice] = useState<{ title: string; message: string } | null>(null);
  const inFlight = useRef(false);

  useEffect(() => {
    const subscription = BatteryNative.addListener('onState', setSnapshot);
    const lifecycle = AppState.addEventListener('change', state => {
      if (state === 'active') { setSnapshot(BatteryNative.snapshot()); setNow(Date.now()); }
    });
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => { subscription.remove(); lifecycle.remove(); clearInterval(timer); };
  }, []);

  async function perform(action: DashboardAction) {
    if (action === 'dismiss') { setNotice(null); return; }
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    try {
      switch (action) {
        case 'refresh': BatteryNative.refresh(); break;
        case 'authorize': BatteryNative.authorize(); break;
        case 'stopBackground': BatteryNative.stopBackground(); break;
        case 'startBackground': {
          const permission = await BatteryNative.requestBackgroundPermissions();
          if (!permission.granted) {
            setNotice({ title: t('batterySync.background'), message: t('batterySync.backgroundPermission') });
            break;
          }
          BatteryNative.startBackground();
          break;
        }
        case 'openHost': BatteryNative.openHost(); break;
        case 'openSettings': BatteryNative.openSettings(); break;
        case 'share': await Share.share({ message: BatteryNative.diagnostics(), title: t('batterySync.shareStatus') }); break;
      }
    } catch { setNotice({ title: t('common.operationFailed'), message: t('batterySync.actionFailed') }); }
    finally { setBusy(false); inFlight.current = false; }
  }

  const wearableLevel = visibleWearableLevel(snapshot);
  const charging = (value: boolean | null) => value === null ? t('common.unknown')
    : t(value ? 'batterySync.charging' : 'batterySync.notCharging');
  const updated = snapshot.updatedAt === null ? t('batterySync.notRead')
    : new Date(snapshot.updatedAt).toLocaleTimeString(i18n.language, { hour: '2-digit', minute: '2-digit' });
  const labels = Object.fromEntries([
    ...['title', 'phone', 'wearable', 'refresh', 'background', 'backgroundActive', 'backgroundInactive',
      'openHost', 'connectionHint', 'authorize', 'shareStatus'].map(key => [key, t(`batterySync.${key}`)]),
    ...['sync', 'connections', 'computerDevices', 'computerWaiting', 'computerEmpty', 'reading',
      'authorizationHint', 'settingsHint', 'shareHint'].map(key => [key, t(`batterySync.ui.${key}`)]),
    ...['computer', 'keyboard', 'mouse', 'trackpad', 'headphones', 'speaker', 'controller', 'accessory']
      .map(key => [`device.${key}`, t(`batterySync.ui.deviceTypes.${key}`)]),
    ['openSettings', t('common.openSettings')], ['dismiss', t('common.dismiss')],
  ]);

  return <>
    <StatusBar barStyle={dark ? 'light-content' : 'dark-content'} />
    <BatteryDashboard style={styles.root} labels={labels} onAction={event => void perform(event.nativeEvent.action)}
      model={{
        phoneName: snapshot.phoneName?.trim() || t('batterySync.phone'), phoneLevel: validBatteryLevel(snapshot.phoneLevel),
        phoneStatus: charging(snapshot.phoneCharging), phoneCharging: snapshot.phoneCharging === true,
        wearableName: snapshot.wearableName?.trim() || t('batterySync.wearable'), wearableLevel,
        wearableStatus: snapshot.status === 'ready' && wearableLevel !== null ? charging(snapshot.wearableCharging)
          : t(`batterySync.status.${snapshot.status === 'ready' ? 'unavailable' : snapshot.status}`),
        wearableCharging: wearableLevel !== null && snapshot.wearableCharging === true,
        lastRead: t('batterySync.ui.lastReadValue', { time: updated }), backgroundRunning: snapshot.backgroundRunning,
        controlsEnabled: !busy, readEnabled: !busy && snapshot.status !== 'reading', reading: snapshot.status === 'reading',
        computerConnected: snapshot.computerUpdatedAt !== null && now - snapshot.computerUpdatedAt <= 180_000,
        computerDevices: visibleComputerDevices(snapshot, now), noticeTitle: notice?.title ?? '', notice: notice?.message ?? '',
      }} />
  </>;
}

const styles = StyleSheet.create({ root: { flex: 1 } });
