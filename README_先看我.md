# REx 手机红外遥控工程 · v0.1 Android 16 联调版

**重要：此 ZIP 不是 APK，不能直接当作安装包。** 当前执行环境没有 Android SDK / Gradle / APK 签名工具，也无法下载，因此未编译、未在一加 Ace 3V 真机安装验证。源代码齐备，附带 GitHub Actions **云端**构建 APK 的脚本（电脑不必安装 Android Studio）。

## 已实现的源代码功能

- Android 原生 Java，无外部 UI 库；针对 Android 16 / ColorOS 16.0.5（一加 Ace 3V / PJF110）设计。
- 深色科幻粒子智能体：600 多个动态粒子，语音服务检测到语音后改变粒子幅度。
- 左下角空白处 650ms 的相邻点击间隔下三击切换模式；按键模式检测到语音“Rex”会切回粒子界面（仅在应用的 Activity 处于前台时）。
- 手动按键：前进0.6、后退0.4、左移0.4、右移0.4（m/s）；左/右转0.4（rad/s）；站立、停止保持站立、闪身步、转个圈、激活、失能。
- 单次 NEC 延长地址 `0x3B4C` / 38kHz 的红外发射，命令表在 `nanopc/ir_codes.json`；本地接口不可用时提示，不伪称发射成功。站立/激活/失能要求二次确认。
- Android 麦克风前台服务 + Android 提供的**离线**语音识别（若手机提供相应引擎）；不把原始音频发送给自建服务器。
- 语音解析只用于 **展示/自动切屏**，**不触发运动/失能红外**。这是有意的安全限制。

## 尚未实现、必须如实说明

1. **本人声音验证尚未实现。** Android SpeechRecognizer 只能转写语音，无法证明说话的人就是你。未来需集成声纹模型、本机注册、活体/重放对抗和误识别评估。因此本版语音控制**锁定**，不会冒充声纹认证完成。
2. **可靠的非人声过滤尚未实现。** 只有语音引擎检测到开始说话才响应音量，实际降噪和 VAD 取决于设备。音乐、录音、人声电视仍可能被识别；不能用于授权任何电机运动。
3. **Android SpeechRecognizer 不是官方推荐的常驻唤醒词引擎。** 本版为了测试会重新启动离线识别；ColorOS 系统可能限制后台录音、重启语音服务或杀进程。正式使用应替换为经验证的本地唤醒词/VAD 引擎。
4. **NanoPC 红外接收内核设备未启用。** 用户输出里没有 `/dev/lirc*`、`/sys/class/rc/rc*`，仅能证明板载硬件存在，不能证明驱动可用。
5. **没有把红外桥接到真实 ROS2 电机动作。** 没有运动命令消费者；目前完成的是手机单向发射端的工程。`nanopc/` 下仅有只读驱动检查与帧参考程序。
6. 机器人接受“持续运动直到停止”的设计需求，但红外是单向链路，**停止帧丢失会导致无限运动**。正式控制必须先建立独立的硬件断电、实时故障保护和失联/运动保护机制，不能关闭关键保护来满足无时限运动需求。

## 不安装 Android Studio，在 GitHub 云端生成可安装的调试 APK

需 GitHub 账户。此构建过程在 GitHub 的 Linux 机器上执行，不需要 Windows 安装 IDE：

1. 先把 ZIP 解压。创建一个**私有** GitHub 仓库 `rex-android-remote`。
2. 将解压后目录中的 `settings.gradle`、`build.gradle`、`app/`、`.github/workflows/build-apk.yml` 等上传到**仓库根目录**。注意 `.github` 是隐藏目录，需要一并上传，不能只把 ZIP 文件本身提交进去。
3. 到仓库 `Actions` 标签页，选择 `REx Android test APK` → `Run workflow`。
4. 等该次工作流显示成功后，在该次运行页面 `Artifacts` 中下载 `REX-Android-debug-APK`，解压即可得到 `app-debug.apk`。
5. 用手机浏览器下载 APK，选择允许安装来自这个来源的应用。**只安装由你自己的 GitHub 仓库构建的文件。**

构建所使用的 Android Gradle Plugin 为 8.10.1、Gradle 8.11.1、Android SDK 36、Java 17。`app-debug.apk` 采用自动生成的调试签名，不是正式发布签名。每次新的 GitHub runner 所用调试签名可能不同，升级时可能需要卸载旧版。

注意：由于本环境无法安装 Android SDK，未对 GitHub 云端实际 build 成功进行验证。若云端失败，把 Actions 中的 **build error** 复制过来即可排查，不要求安装 Android Studio。

## 运行方式

- 打开 REx，主页显示动态粒子。顶部显示 `ConsumerIrManager` 是否暴露可用的 38kHz 红外发射器。
- 点击界面下部「点此启用后台语音预览」，授予麦克风权限。长按同一区域超过 1.2 秒停止语音服务。
- 左下角空白处连点三次，进入按键模式。按键点击发送**一次 NEC 帧**；未确认接收之前不可认为机器人执行。
- 语音服务听到 `Rex` 时会尝试把**当前前台应用**切回粒子页。App 已在系统后台时不能强制拉起界面。
- 语音识别到的具体命令只会显示在界面，不发给机器人。

## 为什么不用酷控 SDK

酷控 SDK 主要提供家电码库与厂商适配。本工程直接使用 Android 标准 `ConsumerIrManager`；部分 ColorOS 设备会限制第三方访问，此时会明确报错，不能保证该机一定成功发射，不能使用未验证的 Oplus 隐藏接口冒充可用。

## 文件清单

- `app/src/main/java/com/rex/remote/RexView.java`：动态粒子 UI、三击、按键
- `app/src/main/java/com/rex/remote/MainActivity.java`：权限、手动红外、语音事件
- `app/src/main/java/com/rex/remote/NecIr.java`：38kHz NEC 编码、发射 API
- `app/src/main/java/com/rex/remote/RexCommand.java`：固定速度/别名/命令码
- `app/src/main/java/com/rex/remote/VoiceService.java`：前台麦克风、语音预览
- `nanopc/ir_readonly_probe.sh`：NanoPC 红外驱动只读排查
- `nanopc/ir_codes.json`：协议编码与含义
- `nanopc/ir_frame_reference.py`：离线 32 位 NEC 校验
- `.github/workflows/build-apk.yml`：云端生成 APK 的工作流

## 明确不改动 REx 实机工程

本 ZIP **未修改** `/srv/rex/rex_ws` 中任何 ROS2 文件，不包含实际电机发射控制路径，不会使能电机，也不接管你目前可站立的 PPO 控制器。
