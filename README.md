# 电伴 · BatMate

将小米手机和穿戴设备的电量同步到 Mac。

Android 应用包名 `com.folio.batterysync.probe`。通过小米官方 XMS Wearable SDK
查询已连接设备的电量和充电状态，手机电量使用 Android 系统接口。不会解绑、重新配对
或创建手环蓝牙连接，也不会修改小米运动健康的数据。

## 使用

1. 保持小米运动健康与手环连接，安装 `.build/battery-sync-0.1.0.apk`。
2. 在 LSPosed 中启用「电伴」，作用域仅选择小米运动健康，然后重启运动健康并恢复设备连接。
3. 点击「读取电量」。若需要授权，点击「授权读取设备状态」并在官方授权界面确认。
4. 确认电量与官方应用一致，检查充电状态和通知、健康同步。
5. 开启「后台读取」，允许附近设备与通知权限；按 HyperOS 设置允许后台运行。
6. 熄屏后等待两分钟，再打开应用检查读取时间。后台每分钟查询一次；设备深度休眠时
   不保证准时执行，不使用唤醒锁。系统重启或强制停止后，需要重新打开并开启后台读取。
7. 如读取失败，使用「分享设备状态」提供结果；导出内容不包含账号、密钥或设备地址。

当前版本只验证读取与后台运行，不包含 Mac 传输或系统电池小组件接入。APK 使用
Expo Android 模板的测试签名，不依赖电脑运行开发服务器。

K60 真机结果见 [DEVICE-TEST.md](./DEVICE-TEST.md)：手机电量与后台服务可用，
官方授权已通过，但小米运动健康 3.59.1 的 SDK 电量响应为 0%，与其界面显示的 68% 不一致。
当前修复版集成 LSPosed API 102 模块，读取官方应用的设备电量缓存。
启用后已在真机读到 68%、未充电，与官方应用和用户报告一致。
返回手机桌面、手环回到表盘后，后台成功读取时间相隔 60,035 毫秒；
拔线熄屏后的稳定性与耗电尚未验证。

## 构建与验证

### 手环端配套应用

`wearable/` 使用小米 Vela 官方工具链，包名与 Android 应用相同，构建时从现有
Android 测试签名导出配套证书。私钥、证书及生成的语言资源不入库。
页面显示本机电量、充电状态、手机连接状态和上次后台读取时间。
采集与通信由 `app.ux` 持有，每 30 秒读取一次，退出页面只解除页面订阅。
应用层采集改动尚未安装到手环；当前方案不依赖手环后台运行。
[官方后台文档](https://iot.mi.com/vela/quickapp/zh/guide/framework/other/background-running.html)
只明确列出音频、上传下载和定位，并要求至少一个受支持的后台接口正在运行；
不能据此保证电量与通信能力维持常驻，也不使用手机快应用的 `system.resident` 接口。
返回表盘至少 90 秒后重新打开页面，对照「上次后台读取」是否在等待期间更新，
不能用重新打开页面后的当前电量证明后台采集成功。
它首先用于验证官方 SDK 的应用身份检查，安装成功不等于电量权限一定可用。

```sh
cd wearable
pnpm install
pnpm build
```

输出 `../.build/battery-sync-wearable-0.1.0.rpk`。可以使用本机 AstroBox API 安装，
参考 [官方安装说明](https://abox.run/docs/usage/cli-and-skills/resource)。
本机 AstroBox 2.2.0 使用 Local API v2，需要在 AstroBox 中批准客户端配对。
2026-10-09 的 npm `astrobox-cli@0.1.5` 仍使用旧接口；本次使用
[官方源码版本](https://github.com/AstralSightStudios/AstroBoxCli/tree/2390cd2697f594fb1f516518b17e84deffc72781)
支持的新版接口，工具与凭证存放在忽略目录 `.build/`。

### Android 应用

使用 Node、pnpm、Python 3、JDK 17 和 Android SDK 37（应用目标版本仍为 36）：

```sh
pnpm install
pnpm typecheck
pnpm lint
pnpm test
pnpm build:android
```

语言目录位于仓库根目录 `locales/`，修改文案后运行 `pnpm i18n:validate`。
原生语言资源与 SDK 由构建脚本生成。官方 SDK 二进制不会入库；校验不符时停止构建。

## SDK 来源

- [官方接口文档](https://vela-docs.cnbj1.mi-fds.com/vela-docs/files/%E5%B0%8F%E7%B1%B3%E7%A9%BF%E6%88%B4%E7%AC%AC%E4%B8%89%E6%96%B9APP%E8%83%BD%E5%8A%9B%E5%BC%80%E6%94%BE%E6%8E%A5%E5%8F%A3%E6%96%87%E6%A1%A3_1.4.pdf)
- [官方示例与 SDK](https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip)
- SDK SHA-256：`9c40fd1c5409bb948523d503af71e2978ae522c35636afe7d64f474c0f6bc195`

电量状态只支持查询，充电和连接状态支持订阅；订阅事件触发补查询。后台不发起授权。
读取失败时显示明确状态，不使用缓存百分比冒充当前电量。过期查询结果不会覆盖新状态。
SDK 有身份校验失败返回；能否独立授权及兼容 Android 17、HyperOS 4、手环 10 Pro，
以真机结果为准。

## 手机侧电量缓存适配

模块使用 [libxposed API 102.0.0](https://central.sonatype.com/artifact/io.github.libxposed/api/102.0.0)，
固定二进制 SHA-256 为 `423484a6e1807e7a423c4b88fcd8176d104318259d91791877fed88fe91479d0`。
API 仅用于编译，不打包进应用；入口和作用域按
[LSPosed 官方规范](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
配置，仅作用于 `com.mi.health:device`。

适配限定运动健康 3.59.1，只处理本应用的电量和充电状态查询；保留原服务的配套
应用登记、签名验证和设备状态授权。匹配已连接设备后，从 `DeviceInfo.BatteryInfo`
读取运动健康的当前缓存并沿原查询回调返回，不修改缓存、不建立蓝牙连接，也不触发
额外的手环状态请求。未知电量、断线、身份或权限不匹配时保留原服务行为。

此版本的手机应用要求 3.59.1 返回缓存适配来源标记；未启用适配时不展示 SDK 的
错误百分比。缓存中的真实 0% 仍有效，不把所有零电量判定为异常。「上次读取」
表示手机应用读取缓存的时间，电量本身的更新频率由运动健康决定。其他运动健康
版本继续走官方 SDK，未验证兼容性。
