# 耳畔 · erpan

**在手机上和 AI 语音通话，不占住你的屏幕。**

耳畔是一款免费开源的 Android 语音客户端。你可以一边玩游戏、看小说，一边和聊天前端里的 AI 说话；也可以关闭麦克风，只听它的回复。

[下载](https://github.com/qfyingque/erpan/releases) · [使用说明](使用说明.md) · [开发接入](开发接入.md) · [更新说明](CHANGELOG.md) · [隐私](PRIVACY.md)

当前整理版本：**code68 / 0.3.37-preview**。功能沿用 code67，统一教程、插件升级说明与发布材料；本次手机长回复、尾音和连续播放尚待试听，见 [验证范围](VERIFICATION.md)。

## 能做什么

- 后台语音通话、悬浮球开关麦、发送前确认，也可只听回复。
- 识别与合成分别配置，保存多套声音方案，结束通话后切换。
- 群聊按成员绑定不同声音，依次朗读；需要前端提供角色名称。
- 声音收藏：最近声音可直接试听、暂停、拖动进度，收藏原音频、复制文字或导出 WAV。
- 音色独立页面，支持搜索、添加和群聊绑定；兼容服务可刷新电脑声音目录。
- 悬浮字幕支持原有带框与透明样式、字号和颜色，支持的合成路线按播放段落跟随。
- 接入云端语音，以及兼容网关后的本地 Qwen3-TTS / IndexTTS2。
- ElevenLabs 客户端提供 v4 Turbo、v4、Flash v2.5、v3 选项；实际模型和声音权限以服务账号为准。
- 本机「首句先读」首标点提交，后续合段只提交完整标点前缀，减少流式文字被截断在词中间的情况。

## 安装与升级

Android 8.0 及以上。旧用户覆盖安装同签名 APK，保留配置；先结束语音会话。

1. 在 Operit 内确认所选聊天能正常文字回复。
2. Operit → 工具 → 包管理 → ＋，导入 `phone10-mobile-voice.js`，启用并运行 `setup`。
3. 耳畔 → 连接配置，选择聊天，分别填写识别、合成和声音信息，保存后开始通话。

**从 code57 及更早版本升级：APK 与配套 JS 一起更新，并运行 setup。** 已导入 code58 或之后配套 JS 的用户无需重复导入；不确定插件版本时可更新为随包文件。插件沿用原工具包和工作流，详细步骤见使用说明。

## 免费开源与适配

耳畔代码采用 MIT 许可证，安装耳畔不收费。聊天、识别与合成服务由你自行配置，云端用量和音色权限以服务商为准，本地模型需要自行部署。包内不含 API Key 或私人音色；包含的本地检测模型与第三方许可见 [第三方声明](THIRD_PARTY_NOTICES.md)。

当前提供 Operit 适配。其他聊天前端需要实现各自的发送、回复、完成及取消适配；自建语音模型需要实现兼容接口，见 [本地语音接入](LOCAL-TTS-接入说明.md)。完整服务端语音模型和网关不在本仓库内。

## 编译

JDK 21、Android SDK 36、Gradle Wrapper 9.1.0；插件测试使用 Node.js 22+。首次构建需联网下载依赖。

```powershell
.\gradlew.bat :mobilevoice:testDebugUnitTest :mobilevoice:assembleDebug :mobilevoice:lintDebug
node --test tests/*.test.mjs
```

产物为 `mobilevoice/build/outputs/apk/debug/mobilevoice-debug.apk`。分发包沿用维护者原有测试签名；自行编译通常使用不同签名，不能直接覆盖，见 [开发说明](CONTRIBUTING.md)。保留 [MIT 许可证](LICENSE) 与第三方声明。
