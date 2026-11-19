# REx Android v0.3 — 独立离线监听与双层粒子

适配目标：一加 Ace 3V / PJF110，Android 16 / ColorOS 16.0.5。

## 这次真正改了什么

1. `app/src/main/java/com/rex/remote/VoiceService.java`：删除对 Android `SpeechRecognizer` 的依赖，改为 **内置 Vosk 中文识别库 + `AudioRecord` 16kHz 连续录音**。解析离线最终识别文本，等待 Rex / 瑞克斯 / 雷克斯唤醒，保持 15 秒后恢复待机；基于 Vosk 语音终点判断命令结束，实时状态会显示在界面。尝试系统噪声抑制；只有识别出语音片段才让粒子产生说话波动。第一回运行从 APK 资源解压中文模型到私有目录。失败清晰报错。
2. `app/src/main/java/com/rex/remote/MainActivity.java`：删除选择手机系统/离线引擎弹窗，获得麦克风权限后直接启动内置离线引擎。
3. `app/src/main/java/com/rex/remote/RexView.java`：外层 1940 个红色粒子，内层 1248 个独立粒子；内核尺度固定为外层 1/4，轮廓是不规则颗粒丝线团，不是一个圆球/白光核心。整体提亮。待机时大小为唤醒的 70%，两层各自刚性自转、各层内部相对静止；唤醒后粒子相对运动、不同轨迹、随语音强烈形变。无三道圆环。按键模式唤醒切换保留。
4. `app/build.gradle`：升为 `0.3.0-offline`，添加 `vosk-android 0.3.75` + JNA，Gradle **构建期间**从 Vosk 官方 HTTPS 下载约 42MB `vosk-model-small-cn-0.22`，校验包含模型文件，并将 ZIP **打包进 APK**。只构建 arm64-v8a，适合你的手机。
5. `app/src/main/AndroidManifest.xml`：去掉 Android 系统识别服务查询。保持麦克风前台服务声明。

其它文件，例如 `RexCommand.java`、`NecIr.java`、`nanopc/` 未改。Android App 没有 `INTERNET` 权限。**模型的下载发生在 GitHub 云端编译时，而不是用户手机上。** 因模型存在，v0.3 APK 会明显大于之前的 33KB（仅模型压缩包约 42MB）。

## 现有 GitHub 仓库升级（不用上传 .github）

1. 下载 `REX_Android_v0_3_update_existing_GitHub.zip`，Windows 右键「全部解压」。
2. 打开你现有的私有 GitHub 仓库。点击 Add file → Upload files。
3. **把解压后 `app` 文件夹拖进去**，保持其内部路径，检查文件确实被更新到 `app/src/main/...`、`app/build.gradle`；提交更改。
4. 沿用之前成功的 `.github/workflows/build-apk.yml`，点击 Actions，运行原来的 REx Android 工作流。
5. **第一次构建要从官网获取约 42MB 模型**。日志里应该出现 `REx: downloading official 42MB offline Chinese model` 和 `REx: offline model bundled successfully`。如果 Gradle 无法下载模型，构建会失败；**不会成功产出一个缺模型的假离线 APK**。
6. Actions 完成后下载 Artifacts → 解压 → `app-debug.apk`。用手机文件管理器安装；同一签名可覆盖旧版，否则先卸载旧调试版。
7. 手机上点击「开启监听」，授权麦克风。首次加载和解压模型时界面会提示，之后说 **「瑞克斯」/「雷克斯」/「Rex」**，再说「前进」或「停」检查**文字及粒子反应**。按键模式也能通过唤醒词返回粒子界面。

**这里交付的是可云端编译的完整源码，不是已完成编译的 APK。** 当前本地执行环境没有 Android SDK，无法直接验证 APK 安装/ColorOS 实机表现。若 CI 失败，直接复制首个红色错误日志，不要反复重新上传工程。

## 需要坦诚说明的限制

- Vosk 这次实现了**不依赖 ColorOS 系统服务的连续本地普通话语音识别**，不是手机 OEM 的超低功耗硬件唤醒服务。首次解压和识别会消耗更多内存/电池。Android 16 要求从前台合法启动麦克风前台服务，系统仍可能中止进程；强制停止 App 后不再监听。
- Vosk 的声学解码 + 简单能量门控并非 100% 的「人声与非人声分类器」，电视人声、音乐唱词仍有误触发可能。要求更严格时需要专门的 VAD + 声纹 + 防录音回放验证。
- **本人声纹验证尚未实现**，所以识别出的运动指令**只显示文字，不发射红外**。绝不冒充“只认本人”。按键保持此前 NEC 红外**单次发射联调**逻辑，不能证明 NanoPC 已收到；NanoPC 当前红外接收设备尚未被 Ubuntu 注册，也尚未接 ROS2。
- 不能依赖单向红外的「停」作为唯一停止机制。正式连接行走必须在机器人侧加入独立可靠的运动停机保护；绝不能直接以这版控制真实机器人。
- 本地离线中文模型：`vosk-model-small-cn-0.22`，Vosk 官方列出的 Apache 2.0 模型。下载地址：<https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip>。Vosk SDK：<https://github.com/alphacep/vosk-api>。

## 本地完成的检查

- `RexCommand` 中文、英文、别名与白名单 Java 测试通过；
- 核查全部源文件中无 Android `SpeechRecognizer` 运行时调用，且语音 `VoiceService` 不调用 `NecIr.send()`；
- 核查新粒子结构（1940 外层 + 1248 内层，0.70 待机尺寸，0.25 内核比例）；
- 由于本地无 Android SDK 和外部模型下载能力，**没有进行 Android APK 构建或手机真机验收**。
