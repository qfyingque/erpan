# 开发说明

源码根目录注册 `:mobilevoice`，包含 Android 应用、Operit 连接插件与测试。

## 代码位置

| 位置（`mobilevoice/src/main/java/com/huigu/phone10/mobile/` 下） | 职责 |
| --- | --- |
| `ErpanHome`、`ErpanConfig`、`ErpanTheme`、`ErpanPresentation` | 首页、表单、视觉及展示状态 |
| `MainActivity`、`VoiceService` | Android 界面与前台会话生命周期 |
| `SpeechTranscript` | 识别原文与可选声音线索；边界解析及同消息呈现 |
| `VoiceConversation` | 一轮语音的识别、提交、回复、合成及取消 |
| `BailianSpeech`、`MiniMaxSpeech`、`MossSpeech`、`VolcengineSpeech`、`VolcengineRecognition`、`CloudSpeech`、`CloudEndJudge` | 服务商协议和可选结束判断 |
| `PcmRecorder`、`PcmPlayer` | 手机收音、PCM 播放 |
| `OperitBridge`、`OperitProvider` | 本机授权、请求与回复事件 |
| `SettingsStore`、`SpeechConfig`、`VoiceProfiles` | 加密配置、语音方案和配置校验 |
| `FloatingCaptions`、`CaptionBuffer`、`CaptionLayout` | 悬浮字幕窗口、文本和布局 |
| `VoiceAudioFocus`、`VoiceAudioRoute` | 游戏声音共存及耳机路由 |
| `Phone10AvatarStore`、`Phone10MicOverlay` 等 | 头像、悬浮球与手势 |

`operit/phone10-mobile-voice.js` 是分发使用的广播插件。当前插件会加载 APK 内的原生 worker，将长回复移出广播入口；历史读取 helper 由 JS 内嵌，源码在 operit/native。Operit 1.12.2 已验证加载兼容，持续长任务仍需实机验证。包名与部分内部类沿用 Phone10，以保留安装和设置兼容。

## 修改与验证

### 可选声音线索

code78起通过首页「传递情绪线索」「传递停顿信息」分别控制，默认关闭。配置由MobileSettings的sendEmotionHints/sendTimingHints沿原加密设置存储；VoiceService将快照传给VoiceConversation，由SpeechTranscript.forChat按两项选择生成同一条消息。普通百炼详细返回的受支持情绪/时间信息才会附加；缺字段保持原转写。开关不会让识别模型获得新能力，不新增情绪模型请求。确认改字后沿withText丢弃旧元信息，智能结束判断只看转写原文。

先运行 README 中的 Android 和 Node 测试。修改协议时检查请求次数、顺序、超时、取消及迟到事件；修改界面与悬浮球时需要真机检查权限、头像、麦克风状态和结束语音。

本仓库即独立源码。构建结果位于 `mobilevoice/build/`；提交时遵循根目录 `.gitignore`，保持源码与本机配置分开。

公开签名密钥由维护者保管；不要把 `.jks`、`.keystore`、`.env` 或 `local.properties` 放进版本库。自行编译的 APK 使用你本机的签名，通常不能覆盖其他维护者签名的安装包。

## 许可与发布边界

保留根目录 `LICENSE` 中现有的 LiveKit 模板 MIT 声明，以及 `THIRD_PARTY_NOTICES.md`。依赖和模型许可各自适用。本项目新增代码以 MIT 许可提供，署名为 Erpan contributors；LiveKit 原署名保留。分发二进制时同时保留其依赖的许可文件。
