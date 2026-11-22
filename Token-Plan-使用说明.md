# 耳畔 0.3.41 · Token Plan 接口测试版（code75）

此版增加阿里云百炼 Token Plan 的语音识别与流式语音合成接法。已通过的本地检查与真实账号验证分开记录；尚未用群友账号和荣耀 X30 实测。

## 手机怎么装

安装本目录中的 APK，覆盖已有耳畔即可。不要卸载旧版或清除数据。安装包沿用原包名和签名，保存的聊天、声音方案和密钥仍沿用原存储。

此包以完整 code74 为基础，保留 code73 声音调节与 code74 打断候选修补；没有删减录音或原生识别库。原 APK 和配置继续保留，不能通过卸载来回退。

已经正常连接 Operit 的人不需要为了本次套餐适配更新插件。本次没有修改 O 插件，也没有加入主动通话。

## 套餐配置

1. 打开耳畔的「连接配置」，切到语音通话模式后可同时看到识别和合成。
2. 语音识别服务商选择「阿里云百炼」，点「填入 Token Plan 套餐配置」。填写你自己的套餐专属 API Key。
3. 语音合成同样选择「阿里云百炼」，点「填入 Token Plan 套餐配置」。填写同一份套餐 Key，或点「使用上面的百炼识别地址和密钥」。
4. 先保留默认音色，再检查连接并保存方案。测试合成也会消耗额度。

预填内容：

| 配置 | 内容 |
| --- | --- |
| 识别、合成地址 | https://token-plan.cn-beijing.maas.aliyuncs.com |
| 识别模型 | qwen-audio-3.0-asr-flash |
| 合成模型 | qwen-audio-3.0-tts-plus |
| 默认音色 | longanlufeng（龙安陆风） |

也接受控制台复制的 `https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`，耳畔自动选择对应的语音 HTTP 路径。不要填普通 DashScope 地址；套餐 Key 与普通按量 Key 不混用。没有 Key、没有套餐权限或额度不足时仍需在阿里云处理，安装 APK 不会创建这些权益。

识别在说完一段后上传，合成音频边生成边播放；不将此包描述成实时双向语音模型。原百炼 Paraformer/CosyVoice 的 WebSocket 接法继续保留。

## 如果失败

请发：关于耳畔的版本截图、识别/合成分别检查连接后的错误，以及遮住完整 API Key 的配置截图。不要发送密钥。先区分识别、合成、Operit 聊天连接三步，避免反复更换不相关的 O 插件。

## 平台支持范围

官方模型列表包含本版适配的两个模型，但 Token Plan 个人版另有使用范围限制：目前文档限定编程/智能体工具中的交互式使用，并限制自定义应用后端及非交互批量调用。耳畔独立语音客户端是否属于许可范围，需以阿里云确认及账户实际权限为准。本版只做接口适配，不绕过平台限制，也不自动改走按量付费。

依据（2026-11-03核对）：
- https://help.aliyun.com/zh/model-studio/token-plan-personal-overview
- https://help.aliyun.com/zh/model-studio/token-plan-multimodal-gen
- https://help.aliyun.com/zh/model-studio/qwen-audio-asr-http-api
- https://help.aliyun.com/zh/model-studio/qwen-audio-tts-http-api
- https://help.aliyun.com/zh/model-studio/qwen-audio-tts-voice-list
