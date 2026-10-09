package com.folio.batterysync;

import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

// 仅为已授权的电量同步应用提供运动健康当前设备的电量缓存。
public final class MiFitnessBatteryModule extends XposedModule {
    static final String HOST_PACKAGE = "com.mi.health";
    static final String HOST_VERSION = "3.59.1";
    static final String CLIENT_PACKAGE = "com.folio.batterysync.probe";
    static final String SOURCE_KEY = "folio_battery_source";
    static final String SOURCE_VALUE = "mi-fitness-cache-v1";
    private static final String TAG = "FolioBattery";
    private String processName;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!HOST_PACKAGE.equals(param.getPackageName()) ||
                !(HOST_PACKAGE + ":device").equals(processName)) return;
        try {
            Adapter adapter = new Adapter(param.getClassLoader());
            hook(adapter.query).setId("folio-battery-cache").intercept(chain -> {
                boolean handled;
                try {
                    handled = adapter.respond(chain.getThisObject(), chain.getArgs().toArray());
                } catch (Exception error) {
                    log(Log.WARN, TAG, "运动健康电量缓存读取失败：" + error.getClass().getSimpleName());
                    handled = false;
                }
                return handled ? null : chain.proceed();
            });
            log(Log.INFO, TAG, "电量缓存适配已加载");
        } catch (Exception error) {
            log(Log.WARN, TAG, "运动健康版本接口不兼容：" + error.getClass().getSimpleName());
        }
    }

    private static final class Adapter {
        final Method query;
        final Method getCallingPackage;
        final Method currentApplication;
        final Method verifyCaller;
        final Method hasPermission;
        final Object devicePermission;
        final Method currentModel;
        final Method isConnected;
        final Method getDeviceInfo;
        final Method getDid;
        final Method getBatteryInfo;
        final Method getBattery;
        final Method isCharging;
        final Method itemType;
        final Method success;
        Boolean compatibleVersion;

        Adapter(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> service = loader.loadClass("zyt");
            Class<?> item = loader.loadClass("com.xiaomi.xms.wearable.node.DataItem");
            Class<?> permission = loader.loadClass("com.xiaomi.xms.wearable.auth.Permission");
            Class<?> callback = loader.loadClass("q1e");
            Class<?> model = loader.loadClass("com.xiaomi.fitness.device.manager.DeviceModel");
            Class<?> info = loader.loadClass("com.xiaomi.fitness.device.manager.bean.DeviceInfo");
            Class<?> battery = loader.loadClass("com.xiaomi.fitness.device.manager.bean.BatteryInfo");
            query = method(service, "Z0", String.class, item, callback);
            verifyCaller = method(service, "e5", String.class);
            hasPermission = method(service, "m5", permission, String.class);
            devicePermission = permission.getField("DEVICE_MANAGER").get(null);
            currentModel = method(service, "g5");
            isConnected = method(model, "isDeviceConnected");
            getDeviceInfo = method(model, "getDeviceInfo");
            getDid = method(info, "getDid");
            getBatteryInfo = method(info, "getBatteryInfo");
            getBattery = method(battery, "getBattery");
            isCharging = method(battery, "isCharging");
            itemType = method(item, "a");
            success = method(callback, "B1", item, Bundle.class);
            getCallingPackage = method(loader.loadClass("com.xiaomi.xms.wearable.extensions.ExtensionsKt"), "getCallingPackage");
            currentApplication = method(Class.forName("android.app.ActivityThread"), "currentApplication");
        }

        boolean respond(Object service, Object[] args) throws Exception {
            if (!CLIENT_PACKAGE.equals(getCallingPackage.invoke(null))) return false;
            if (compatibleVersion == null) {
                Application app = (Application) currentApplication.invoke(null);
                if (app == null) return false;
                compatibleVersion = HOST_VERSION.equals(app.getPackageManager()
                        .getPackageInfo(HOST_PACKAGE, 0).versionName);
            }
            if (!compatibleVersion) return false;
            int type = (Integer) itemType.invoke(args[1]);
            if (type != 5 && type != 2) return false;

            // 保留原服务的配套应用签名验证与设备状态授权检查。
            String nodeId = (String) args[0];
            if (!Integer.valueOf(1).equals(verifyCaller.invoke(service, nodeId)) ||
                    !Boolean.TRUE.equals(hasPermission.invoke(service, devicePermission, nodeId))) return false;
            Object model = currentModel.invoke(service);
            if (model == null || !Boolean.TRUE.equals(isConnected.invoke(model))) return false;
            Object info = getDeviceInfo.invoke(model);
            if (info == null || !nodeId.equals(getDid.invoke(info))) return false;
            Object battery = getBatteryInfo.invoke(info);
            if (battery == null) return false;
            int level = (Integer) getBattery.invoke(battery);
            if (level < 0 || level > 100) return false;

            Bundle data = new Bundle();
            data.putString(SOURCE_KEY, SOURCE_VALUE);
            if (type == 5) data.putInt("battery_status", level);
            else data.putBoolean("charging_status", (Boolean) isCharging.invoke(battery));
            success.invoke(args[2], args[1], data);
            return true;
        }

        private static Method method(Class<?> owner, String name, Class<?>... parameters)
                throws ReflectiveOperationException {
            Method value = owner.getMethod(name, parameters);
            value.setAccessible(true);
            return value;
        }
    }
}
