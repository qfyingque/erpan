# 开发说明

源码根目录注册 `:mobilevoice`，包含 Android 应用、Operit 连接插件与测试。

## 代码位置

| 位置（`mobilevoice/src/main/java/com/huigu/phone10/mobile/` 下） | 职责 |
| --- | --- |
| `ErpanHome`、`ErpanConfig`、`ErpanTheme`、`ErpanPresentation` | 首页、表单、视觉及展示状态 |
| `MainActivity`、`VoiceService` | Android 界面与前台会话生命周期 |
| `SpeechTranscript` | 识别原文与可选声音线索；边界解析及同消息呈现 |
| `VoiceConversation` | 一轮语音的识别、提交、回复、合成及取消 |
| `BailianSpeech`、`MiniMaxSpeech`、`CloudSpeech`、`CloudEndJudge` | 服务商协议和可选结束判断 |
| `PcmRecorder`、`PcmPlayer` | 手机收音、PCM 播放 |
| `OperitBridge`、`OperitProvider` | 本机授权、请求与回复事件 |
| `SettingsStore`、`SpeechConfig` | 加密配置及配置校验 |
| `Phone10AvatarStore`、`Phone10MicOverlay` 等 | 头像、悬浮球与手势 |

`operit/phone10-mobile-voice.js` 是分发使用的广播插件。Java worker 辅助代码保留为实验代码和构建资产；本版插件不加载 worker，不能将其当作已验收路线。包名与部分内部类沿用 Phone10，以保留安装和设置兼容。

## 修改与验证

### 可选声音线索

`SpeechTranscript.kt` 中的 `ENABLE_VOICE_HINTS` 默认是 `false`，普通设置页不显示开关，聊天只收到识别原文。接入情绪识别并验证返回字段后，可手动改成 `true` 并重新构建。开启后，服务商实际返回的受支持情绪和时间信息会附在同一条消息后面；该开关本身不会让识别模型获得情绪识别能力。

先运行 README 中的 Android 和 Node 测试。修改协议时检查请求次数、顺序、超时、取消及迟到事件；修改界面与悬浮球时需要真机检查权限、头像、麦克风状态和结束语音。

本仓库即独立源码。构建结果位于 `mobilevoice/build/`；提交时遵循根目录 `.gitignore`，保持源码与本机配置分开。

公开签名密钥由维护者保管；不要把 `.jks`、`.keystore`、`.env` 或 `local.properties` 放进版本库。自行编译的 APK 使用你本机的签名，通常不能覆盖其他维护者签名的安装包。

## 许可与发布边界

保留根目录 `LICENSE` 中现有的 LiveKit 模板 MIT 声明，以及 `THIRD_PARTY_NOTICES.md`。依赖和模型许可各自适用。本项目新增代码以 MIT 许可提供，署名为 Erpan contributors；LiveKit 原署名保留。分发二进制时同时保留其依赖的许可文件。
