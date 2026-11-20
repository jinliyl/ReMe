# ReMe Android 日记

Kotlin / Compose 原生客户端。Python 服务、模型、ASR 和 TTS 运行在电脑或服务器。
后端业务集中在 [`reme/steps/android_app`](../reme/steps/android_app)，复用现有组件。

## 本机服务

在仓库根目录安装 ReMe 开发依赖，准备可用的 Codex 与语音环境变量。
可以直接读取已有 `.env`，无需复制到新仓库或客户端：

```bash
python -m reme.steps.android_app.serve \
  --env-file /Users/yuli/workspace/ReMe2/.env \
  --workspace /tmp/reme-diary-local \
  --port 23340 \
  --connection-file /tmp/reme-diary-connection.json
```

`--connection-file` 是本机开发用的地址和 Token 文件，权限为 0600，必须放在日记工作区外。
未设置 `ANDROID_APP_TOKEN` 时，此模式生成随机 Token。App 填写地址、端口和文件中的 Token。
模型供应商密钥仅保留在 Python 进程。长期使用请改用稳定工作区并备份 Markdown、资源和 `app_state`。

服务器部署时，在私有环境设置 `ANDROID_APP_TOKEN` 后启动：

```bash
reme start config=android_app workspace_dir=/srv/reme-diary
```

默认监听 `127.0.0.1:2333`，适合放在 HTTPS 网关后。直接提供局域网 HTTP 时，显式加
`service.host=0.0.0.0 service.port=2333`。只运行一个写入进程，所有业务请求均需 Bearer Token。

## 构建

需要 JDK 17、Android SDK Platform 37.2。插件和依赖固定在 `gradle/libs.versions.toml`。

```bash
cd reme_android
./gradlew assembleDebug testDebugUnitTest lintDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。首次连接后，在设置页允许通知；
需要准时提醒时，允许系统“闹钟和提醒”权限。权限不足时使用可能延后的普通提醒。

## 模拟器连接本机

Apple Silicon 使用 ARM64 镜像。Android Studio Device Manager 可以创建虚拟设备；
也可以用 SDK 的 `avdmanager` 与 `emulator`。测试 API 37 可使用 Android 37.0 或 37.2 镜像。
App 填写 `http://10.0.2.2`、端口 `23340`。Android 17 起连接局域网需要用户允许局域网访问，连接页会按需申请。
依据：[Android 本地网络权限](https://developer.android.com/privacy-and-security/local-network-permission)。也可在另一个终端转发后填写 `http://127.0.0.1`：

```bash
adb reverse tcp:23340 tcp:23340
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

手动语音试用时，打开模拟器右侧的 **More → Microphone → Enable Host Microphone Access**；
模拟器默认关闭电脑麦克风，首次开启时 macOS 可能请求麦克风权限。
参见 [Android 模拟器扩展控制](https://developer.android.com/studio/run/emulator-extended-controls)。
下述自动化测试使用音频注入，不读取电脑麦克风。

真实 Compose 集成测试使用专用模拟器和隔离的后端工作区。先构建测试 APK，
再启用模拟器的本机认证 gRPC 接口，用于注入测试语音（不会使用电脑麦克风）：

```bash
./gradlew assembleDebug assembleDebugAndroidTest
emulator -avd <你的测试AVD> -port 5556 -grpc 8556 -grpc-use-token -no-snapshot -timezone Asia/Shanghai
```

准备一段包含“日记”的中文语音，转换成单声道 PCM16 / 16 kHz WAV。
在仓库根目录的 Python 测试环境安装可选依赖 `grpcio`、`grpcio-tools`，然后运行：

```bash
ffmpeg -i <测试语音文件> -ac 1 -ar 16000 -c:a pcm_s16le /tmp/reme-test-speech.wav
PYTHONPATH=. python tests/integration/android_emulator.py \
  --sdk "$ANDROID_HOME" \
  --serial emulator-5556 \
  --connection-file /tmp/reme-diary-connection.json \
  --audio-file /tmp/reme-test-speech.wav \
  --report-dir /tmp/reme-android-emulator-report
```

脚本安装两个调试 APK，将专用 Token 写入 App 私有目录，并转发本机端口。
`LocalIntegrationTest` 通过实际页面连接服务、发送记录，等待真实模型整理，验证任务、想法、
原生定时通知、通知中的“完成”操作与提醒取消；第二项测试通过模拟麦克风、`AudioRecord`、
真实 ASR 和原生播放验证语音链路。仅使用专用测试设备；脚本会修改该模拟器的音频输入、
闹钟权限和屏幕休眠设置。未提供私有连接文件时，直接运行的联网测试跳过。
报告目录包含截图和 JSON 结果。AndroidX Test 的固定版本见版本目录，已验证其 API 37 兼容性。
需要检查系统通知栏时使用完整 AOSP 镜像；ATD 镜像没有 System UI，只适合自动化 API 验证。

## 后端真实测试

在仓库根目录运行：

```bash
PYTHONPATH=. python tests/integration/android_app_real.py \
  --env-file /Users/yuli/workspace/ReMe2/.env \
  --report /tmp/reme-android-real-report.json
```

测试实际 HTTP、ASR、TTS 和 Codex，验证鉴权、重复上传、SSE 断开、历史合并、预研、提醒取消与服务重启。
需要供应商网络和额度；测试数据位于临时目录。

首版支持文字、链接分享和最长两分钟的语音记录。手机离线时保留输入，WorkManager 联网重试；
研究结果在同步后出现。实时通话、图片理解、远程推送留待后续扩展。
模拟器不能替代真机收音、扬声器体验和厂商省电策略测试。
