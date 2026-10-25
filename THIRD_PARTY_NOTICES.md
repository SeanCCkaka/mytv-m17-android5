# 第三方版权与源码来源

核验日期：2026-10-25。本目录是为天猫魔盒 M17 制作的 ARMv7 兼容测试分支，保留原作者版权，不代表原作者或 VideoLAN 官方发布。

## 应用上游

基于 [zwc456baby/my-tv-webview](https://github.com/zwc456baby/my-tv-webview/tree/600209bc4bff1a74462a4d36c87ef44f66535962) 的公开提交 `600209bc4bff1a74462a4d36c87ef44f66535962`。原始许可为 MIT，版权行是 `Copyright (c) 2025 李宗英`；全文保留在 [LICENSE](LICENSE) 和 [licenses/MIT-upstream.txt](licenses/MIT-upstream.txt)。

本次修改日期为 2026-10-25，增加 LibVLC 软件解码、TextureView 输出、本地画面自检及相应兼容处理。公开源码使用 Media3，作者 v1.0.3 发布 APK 使用 Firefox/Gecko；本分支及其测试 APK **不是该 Firefox APK 的可重现重建**。编译通过不表示电视实测或直播源可用。

## LibVLC 3.6.5

固定依赖：`org.videolan.android:libvlc-all:3.6.5`，来自 [Maven Central 官方发布目录](https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.6.5/)。本分支不修改 AAR 内的原生二进制，构建时只打包 `armeabi-v7a`。

| 内容 | 已核验的许可与版本 |
| --- | --- |
| Java 绑定 | 原始源码文件声明 LGPL-2.1-or-later；POM 标注 LGPL 2.1。版权为 VLC authors、VideoLAN、VideoLabs 等，逐文件许可头保留在 `licenses/LibVLC-Java-copyright-headers.txt`。 |
| ARMv7 `libvlc.so` | 内嵌 VLC 版本字符串为 `3.0.22-rc1-37-gfe81489755`。FFmpeg 版本为 4.4.5，构建参数包含 `--enable-gpl`；libavcodec、libavformat、libavutil、libswscale、libpostproc 明确报告 GPL version 2 or later。证据保留在 `licenses/libvlc-native-license-evidence.txt`。 |
| AAR 内 Lua 脚本 | 多个脚本（如 YouTube 播放列表解析器）声明 GPL-2.0-or-later；原始许可头保留在 `licenses/LibVLC-Lua-copyright-headers.txt`。 |

**原生库含 GPL 组件，所以整个测试 APK 不能仅标为 MIT，也不能仅凭 Maven POM 称其全部为 LGPL。** VideoLAN 的该版本构建脚本还把默认 contrib 许可配置标为 GPLv3；这是一项构建配置证据，不能替代对所有原生依赖的逐项核验。见 [官方 libvlc-3.6.5 构建脚本](https://github.com/videolan/vlc-android/blob/libvlc-3.6.5/buildsystem/compile.sh) 与 [VideoLAN 许可说明](https://docs.videolan.me/vlc-user/en/support/faq/legalconcerns.html)。

所用 AAR SHA-256：

```text
6a15a1f7acd0738a31acce0c7e5eb6f5f340b62e6b64d8f83df547da56a44b47
```

该 AAR 的最低 Android API 为 17，包含 ARMv7；API 22 满足其声明的最低版本。3.6.5 适配本项目 compileSdk 34；核验时最新非 EAP 的 3.x 为 3.7.7，其 AAR 要求 compileSdk 36，因此本次未采用。版本声明不能代替 M17 上的实际画面验证。

## 对应源码获取

- Java 绑定：[官方 sources.jar](https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.6.5/libvlc-all-3.6.5-sources.jar)，本地完整副本为 `licenses/libvlc-all-3.6.5-sources.jar`；发布 POM 同目录保留。
- 原生构建入口：[vlc-android 的 libvlc-3.6.5 标签](https://github.com/videolan/vlc-android/tree/libvlc-3.6.5)。其 `buildsystem/compile.sh` 指定 [LibVLC JNI 仓库](https://code.videolan.org/videolan/libvlcjni) 的 `libvlcjni-3.x` 分支、提交 `965e8648717076654f463ae64ee1d008d7ff282d`；在该提交使用 `buildsystem/get-vlc.sh` 和 `buildsystem/compile-libvlc.sh` 获取与构建 VLC 及 contrib。
- VLC 原生源码：[VideoLAN VLC 仓库](https://code.videolan.org/videolan/vlc)，本 AAR 中观察到的原生提交短标识为 `fe81489755`。构建所需补丁、contrib 版本和脚本应以对应 LibVLC JNI 提交为准，不可直接用 VLC 3.0.22 正式版替代。
- FFmpeg 上游：[FFmpeg 4.4.5 源码](https://github.com/FFmpeg/FFmpeg/tree/n4.4.5)。VLC 构建可能应用自己的补丁，仅下载 FFmpeg 上游归档并不等于取得完整对应源码。

**Maven sources.jar 只有 Java 绑定，不包含完整 C/C++、FFmpeg/contrib 源码。** 本交付保留了上述来源与版本证据，但尚未汇集、重建并逐项核验全部原生对应源码。对外重新分发包含这些二进制的 APK 时，应按实际 GPL/LGPL 组件提供完整对应源码、构建脚本和必要版权声明，并满足适用的组合及重新链接条件；不能把“应用源码 + Java sources.jar”当作已经完成全部原生源码义务。本说明不声称是完整依赖清单。

## 随附许可原文

- [MIT](licenses/MIT-upstream.txt)：复制自本项目上游 `LICENSE`。
- [GPL 2.0](licenses/GPL-2.0.txt)：取自 VideoLAN `vlc-android/libvlc-3.6.5` 的 `COPYING`。
- [LGPL 2.1](licenses/LGPL-2.1.txt)：取自 [VideoLAN VLC 的 COPYING.LIB](https://raw.githubusercontent.com/videolan/vlc/3.0.x/COPYING.LIB)。
- [GPL 3.0](licenses/GPL-3.0.txt)：取自 [FFmpeg 官方仓库的 COPYING.GPLv3](https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/COPYING.GPLv3)。

这些文本保留各自的免责条款。原项目其他依赖仍受其各自许可约束；上游 MIT 许可不改变第三方组件的许可。
