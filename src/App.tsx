import { useEffect, useState } from 'react';
import { Alert, AppState, Share } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { Button, Host, LazyColumn, ListItem, OutlinedButton, Surface, Text } from '@expo/ui/jetpack-compose';
import { fillMaxSize, fillMaxWidth } from '@expo/ui/jetpack-compose/modifiers';
import { useTranslation } from 'react-i18next';
import BatteryNative from './battery-native';
import { visibleWearableLevel } from './battery-state';

export default function App() {
  const { t, i18n } = useTranslation();
  const [snapshot, setSnapshot] = useState(() => BatteryNative.snapshot());
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const subscription = BatteryNative.addListener('onState', setSnapshot);
    const lifecycle = AppState.addEventListener('change', state => {
      if (state === 'active') setSnapshot(BatteryNative.snapshot());
    });
    return () => { subscription.remove(); lifecycle.remove(); };
  }, []);

  async function perform(operation: () => void | Promise<void>) {
    setBusy(true);
    try { await operation(); }
    catch { Alert.alert(t('common.operationFailed'), t('batterySync.actionFailed')); }
    finally { setBusy(false); }
  }

  const wearableLevel = visibleWearableLevel(snapshot);
  const percentage = (value: number | null) => value === null ? t('common.unknown') : `${value}%`;
  const charging = (value: boolean | null) => value === null ? t('common.unknown')
    : t(value ? 'batterySync.charging' : 'batterySync.notCharging');
  const updated = snapshot.updatedAt === null ? t('batterySync.notRead')
    : new Date(snapshot.updatedAt).toLocaleString(i18n.language);
  const enabled = !busy && snapshot.status !== 'reading';

  return (
    <SafeAreaProvider>
      <SafeAreaView style={{ flex: 1 }}>
        <Host style={{ flex: 1 }}>
          <Surface modifiers={[fillMaxSize()]}>
            <LazyColumn modifiers={[fillMaxSize()]}>
              <ListItem>
                <ListItem.HeadlineContent><Text>{t('batterySync.title')}</Text></ListItem.HeadlineContent>
                <ListItem.SupportingContent><Text>{t('batterySync.connectionHint')}</Text></ListItem.SupportingContent>
              </ListItem>
              <ListItem>
                <ListItem.HeadlineContent><Text>{t('batterySync.phone')}</Text></ListItem.HeadlineContent>
                <ListItem.SupportingContent><Text>{charging(snapshot.phoneCharging)}</Text></ListItem.SupportingContent>
                <ListItem.TrailingContent><Text>{percentage(snapshot.phoneLevel)}</Text></ListItem.TrailingContent>
              </ListItem>
              <ListItem>
                <ListItem.HeadlineContent><Text>{snapshot.wearableName ?? t('batterySync.wearable')}</Text></ListItem.HeadlineContent>
                <ListItem.SupportingContent><Text>{t(`batterySync.status.${snapshot.status}`)}</Text></ListItem.SupportingContent>
                <ListItem.TrailingContent><Text>{percentage(wearableLevel)}</Text></ListItem.TrailingContent>
              </ListItem>
              <ListItem>
                <ListItem.HeadlineContent><Text>{t('batterySync.chargeState')}</Text></ListItem.HeadlineContent>
                <ListItem.SupportingContent><Text>{charging(snapshot.wearableCharging)}</Text></ListItem.SupportingContent>
              </ListItem>
              <ListItem>
                <ListItem.HeadlineContent><Text>{t('batterySync.lastRead')}</Text></ListItem.HeadlineContent>
                <ListItem.SupportingContent><Text>{updated}</Text></ListItem.SupportingContent>
              </ListItem>
              <Button modifiers={[fillMaxWidth()]} enabled={enabled} onClick={() => void perform(() => BatteryNative.refresh())}>
                <Text>{t('batterySync.refresh')}</Text>
              </Button>
              <OutlinedButton modifiers={[fillMaxWidth()]} enabled={enabled} onClick={() => void perform(() => BatteryNative.authorize())}>
                <Text>{t('batterySync.authorize')}</Text>
              </OutlinedButton>
              <ListItem>
                <ListItem.HeadlineContent><Text>{t('batterySync.background')}</Text></ListItem.HeadlineContent>
                <ListItem.SupportingContent><Text>{t(snapshot.backgroundRunning ? 'batterySync.backgroundActive' : 'batterySync.backgroundInactive')}</Text></ListItem.SupportingContent>
              </ListItem>
              <Button modifiers={[fillMaxWidth()]} enabled={enabled} onClick={() => void perform(async () => {
                if (snapshot.backgroundRunning) { BatteryNative.stopBackground(); return; }
                const permission = await BatteryNative.requestBackgroundPermissions();
                if (!permission.granted) {
                  Alert.alert(t('batterySync.background'), t('batterySync.backgroundPermission'));
                  return;
                }
                BatteryNative.startBackground();
              })}>
                <Text>{t(snapshot.backgroundRunning ? 'batterySync.stopBackground' : 'batterySync.startBackground')}</Text>
              </Button>
              <OutlinedButton modifiers={[fillMaxWidth()]} enabled={!busy} onClick={() => void perform(() => BatteryNative.openHost())}>
                <Text>{t('batterySync.openHost')}</Text>
              </OutlinedButton>
              <OutlinedButton modifiers={[fillMaxWidth()]} enabled={!busy} onClick={() => void perform(() => BatteryNative.openSettings())}>
                <Text>{t('common.openSettings')}</Text>
              </OutlinedButton>
              <OutlinedButton modifiers={[fillMaxWidth()]} enabled={!busy} onClick={() => void perform(async () => {
                await Share.share({ message: BatteryNative.diagnostics(), title: t('batterySync.shareStatus') });
              })}>
                <Text>{t('batterySync.shareStatus')}</Text>
              </OutlinedButton>
            </LazyColumn>
          </Surface>
        </Host>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}
