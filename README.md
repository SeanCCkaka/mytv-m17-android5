# 我的电视 · M17 Android 5.1 兼容版

**可用版本：`v0.1.2-m17-test`（`versionCode = 3`）。已在天猫魔盒 M17、Android 5.1.1、ARMv7 上安装和验证。**

这是基于 [zwc456baby/my-tv-webview](https://github.com/zwc456baby/my-tv-webview) 公开源码制作的设备兼容分支。保留直播播放器和遥控器操作，修复 M17 上的播放器初始化兼容问题，并整理了实际使用的频道列表。

## 使用前先看

- **首次安装后，在设置中关闭“兼容播放（VLC 软件解码）”**，使用已在 M17 上验证流畅的 Media3 自动/硬件解码。全新安装的软件解码开关仍保留早期测试默认值。
- 当前可用版本内置 **39 个去重频道**：央视 16 个、卫视 15 个、浙江频道 8 个。
- 播放时按 **左键** 打开频道列表，**上下**选择，**确定**切台，**返回**关闭。在列表里按左键进入分组栏，再按右键回到频道栏。
- 设置中的“画面自检”会播放 12 秒离线动态图和提示音，可用于区分网络源问题与播放器问题。
- 同一签名覆盖升级会保留频道和设置。自行构建使用的签名可能与已安装测试包不同；不要通过卸载来强行升级，以免丢失配置。

## 已验证的版本与设备

| 版本 | 状态 | 验证范围 |
| --- | --- | --- |
| `v0.1.2-m17-test` | **可用，M17 实机验证通过** | Android 5.1.1 / API 22 / ARMv7；39 个频道；左键频道列表和遥控器切台 |
| 其他机型、系统及 ABI | 尚未实测 | 不能直接视为兼容承诺 |

15 个卫视由使用者在电视上确认可播放；16 个央视和 8 个浙江频道进一步检查了首帧及持续视频输出。左键打开、上下选择、确定切台、返回关闭，均已在魔盒上测试，并由使用者确认遥控器操作正常。

频道清单对应 **2026-10-26** 的网络条件。公开直播地址会变化，短时检查不能保证长期可用；山东卫视曾短暂音画不同步，重新打开后由使用者确认恢复。详细记录见 [验证说明](VALIDATION.md)、[频道核验](CHANNEL-VERIFICATION-2026-10-26.json) 和 [设备兼容说明](docs/COMPATIBILITY.md)。

## 主要修改

- 用 R8 将应用内 Guava 与 YunOS 系统旧版隔离，修复 `ImmutableMap.Builder.buildOrThrow()` 缺失导致的 Media3 初始化失败。
- 保留 Media3 与 LibVLC 3.6.5 两条播放路径；LibVLC 采用视频软件解码和 TextureView 输出。
- 加入本地画面与声音自检。
- 同一频道有限自动换源：每源等待视频首帧最多 10 秒，每轮最多尝试 5 个不同地址。
- 左键直接打开频道列表，频道去重、分组并重新编号。
- 使用独立应用包名 `com.lizongying.mytv0.m17compat`。

本项目修改应用层，不替换系统显示驱动或设备固件。

## 频道导入

[CHANNELS-2026-10-26.json](CHANNELS-2026-10-26.json) 与 APK 内置默认表一致，适用于本分支的频道导入功能。不要将旧项目附带的其他来源列表视为已验证频道。

## 构建

需要 JDK 17、Android SDK Platform 34、Build Tools 34.0.0，以及可访问 Google Maven 和 Maven Central 的网络。设置 `JAVA_HOME` 和 `ANDROID_HOME` 后，在项目根目录执行：

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。此构建变体启用了 R8，且设置为不可调试，保留开发签名机制。**不要关闭 R8，也不要把 `isDebuggable` 改为 `true`**，否则可能破坏 YunOS 上所需的 Guava 隔离。原生库仅打包 `armeabi-v7a`，最低 Android API 为 22。

需要进一步核验隔离结果时执行：

```sh
python3 tools/verify_guava_isolation.py \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --mapping app/build/outputs/mapping/debug/mapping.txt \
  --dexdump "$ANDROID_HOME/build-tools/34.0.0/dexdump" \
  --report app/build/reports/guava-isolation.json
```

已安装的 0.1.2 测试包构建、35 项已有单元测试通过，lint 为 0 错误、109 条既有警告。验证记录内的 APK 哈希对应实际安装测试包，不代表不同签名或不同环境构建会产生相同二进制。

## 来源与许可

上游公开提交：`600209bc4bff1a74462a4d36c87ef44f66535962`。原作者版权及 MIT 许可保留在 [LICENSE](LICENSE)。详细来源见 [UPSTREAM.json](UPSTREAM.json) 和 [原始上游说明](README-UPSTREAM.md)。

该公开提交使用 Media3，与上游曾发布的 Firefox/Gecko APK 实现不同；本分支不声称是那个 APK 的可重现重建。

第三方组件分别遵循各自许可。特别是 LibVLC 原生二进制包含 GPL 组件，不能将整个组合 APK 简单标为 MIT；源码来源、构建入口和随附许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 `licenses/`。
