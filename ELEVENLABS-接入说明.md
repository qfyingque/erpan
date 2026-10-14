# ElevenLabs 接入普通耳畔

本版提供 ElevenLabs 合成服务。保留已有云端和本机声音方案、识别服务、聊天绑定、字幕确认发送和头像功能。平台额度与音色API权限以当前账号为准；验证范围见 VERIFICATION.md。

## 注册与设置

1. 打开 https://elevenlabs.io/app/sign-up 。Google 是快捷登录方式；也可用普通邮箱注册。密码、验证步骤和服务条款由账号本人完成。安装耳畔无需订阅；API 合成及具体音色是否能用，以服务账号权限为准。
2. 注册后在 ElevenLabs 平台选择能使用的声音，复制 Voice ID。不是声音显示名，也不是试听网址；本次可以直接试官方声音，无需克隆或上传参考音频。
3. 在平台 Developers / API Keys 创建具有 Text to Speech 合成权限的 API Key；这是独立于模型聊天和语音识别的密钥。不要放进群、截图、日志或源码。免费可用额度和具体音色权限以账号当前控制台为准。
4. 安装本包后，在耳畔声音方案中另存一个新方案，合成服务选 ElevenLabs，地址保持 https://api.elevenlabs.io/v1 ，填写 API Key、Voice ID 和自定声音名称。识别和聊天设置继续用原配置。
5. 选择 v4 Turbo 或 Flash v2.5（边写边说），也可选 v4 或 v3（分段朗读）。同一个声音可分别保存多套方案，方便比较。点击检查连接会产生一条短句合成，消耗平台额度，但不会播放或发送聊天消息。
6. 最后以手机实际听到短句、长回复、两次连续回复和停止后无残留为验收。构建或模拟接口测试不能证明账号已可用。

## 合成路线

- Flash v2.5：官方 TTS WebSocket 接收陆续到来的文字，回传 Base64 PCM。首条空格初始化、末条空字符串结束，工具等待期间空格保活；一条回复独立连接，结束后关闭。平台仍会缓冲文字，不能承诺首字立即出声。
- v4 Turbo：官方 Text to Dialogue WebSocket（/v1/text-to-dialogue/stream-input）。首条注册一个声音，后续以 inputs 追加原样文字，不给英文 token 之间额外补空格。首个断句标点触发 flush；后续累计至少 80 字遇标点、达到 160 字或文字停顿 500 毫秒时刷新缓冲。这些刷新共用一条连接，不重开独立合成任务。工具等待使用 keep_alive；只有上游明确完成才发送 close_socket。
- v4 / v3：官方 Text to Speech HTTP stream，每段完整文字一个请求，输出音频流。复用既有分段队列和统一播放器；各段独立生成，音色和语气衔接需试听。
- 输出均固定 pcm_24000，24 kHz 单声道 PCM16LE，复用当前播放器。取消会取消当前请求/连接并清空原播放链；客户端不重试已提交文本、不切到其他付费平台。
- Turbo 的 is_final_audio_for_turn 仅是中间一轮音频完成，继续接收后文；只有 is_final 才代表连接合成收尾。Flash 仍使用其原有 isFinal。
- 群聊继续沿用耳畔自己的成员声音绑定和顺序播放器：同一条回复固定一个声音，不在 Turbo 连接中注册多个声音。O 插件沿用 code58，本次升级无需重新导入。

接口仅接受官方 https://api.elevenlabs.io/v1 基址。密钥用 xi-api-key 请求头，不放在 URL 或 WebSocket 消息正文。错误只展示固定提示/HTTP 状态，不转发远端原始响应或凭据。Key 用耳畔既有加密设置保存。

## 长度与等待

Flash / Turbo 客户端一轮文字上限 40000 Unicode 字符；v3 单次 HTTP 段上限 4000，v4 为 10000，正常聊天通过已有分段队列提交。音频每次请求/连接上限 30 分钟。它们是客户端传输限制，不保证平台、听感或账号额度达到同一上限。HTTP 沿用当前 30 秒读取等待和 90 秒请求总限；WebSocket 总限 30 分钟，15 秒保活。Flash 的 inactivity_timeout=180 保持；Turbo 使用新协议固定 20 秒无输入超时，发送 keep_alive 续期。失败不会重播或静默漏读。

## 官方依据（2026-10-07 核对）

- https://elevenlabs.io/docs/api-reference/text-to-speech/stream
- https://elevenlabs.io/docs/api-reference/text-to-speech/v-1-text-to-speech-voice-id-stream-input
- https://elevenlabs.io/docs/eleven-api/guides/how-to/websockets/realtime-tts
- https://elevenlabs.io/docs/overview/models
- https://elevenlabs.io/docs/eleven-creative/playground/text-to-speech
- https://elevenlabs.io/docs/eleven-api/guides/how-to/websockets/realtime-tdd
- https://elevenlabs.io/docs/eleven-api/guides/how-to/websockets/tts-vs-ttd-websockets
- https://elevenlabs.io/docs/api-reference/text-to-dialogue/ttd-websocket

新指南已列 v4 / v4 Turbo；TTD 自动生成 API schema 的介绍仍写 v3，存在文档更新不同步。模型入口按新指南，消息字段按 API schema 核对。是否对当前账号开放、Voice ID 权限、真实声音和延迟仍需用该账号实测；离线协议测试不能代替。

复用项目现有 OkHttp、Gson、协程和 Android 播放器，没有新增依赖。官方示例主用 JS/Python SDK；Android 现有适配器已承担网络与播放，只增加模型对应的消息格式与路由。ElevenLabs 服务本身为闭源付费/额度服务，客户端接入不赋予模型或音色的其他许可。
