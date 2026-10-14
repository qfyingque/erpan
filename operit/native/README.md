# O 的原生历史读取适配器

`NativeHistoryRead.java` 调用 O 现有 `StandardChatManagerTool.getChatMessages`，保留官方选中变体与历史格式。原生 Continuation 等待上限 5 秒；只读，不发送、取消或回滚聊天。JS observe 接收、状态查询、回包与 complete 全程同步，避免依赖曾在实机广播中卡住的 JS 异步恢复。

`native-read.jar` 是 Android DEX jar，已以 Base64 嵌入 `../phone10-mobile-voice.js`。插件首次使用时保存到 O 自己的 files 目录，写入前设为只读、校验 SHA256，再通过 O 支持的 `Java.loadJar` 加载；不依赖下载地址或 code_cache。原生 Kotlin 依赖来自 O，不打包额外运行库。

修改原生代码后，设置 `JAVA_HOME`（JDK 21）、`ANDROID_HOME`（含 Android 36/build-tools 36.0.0）和 `KOTLIN_STDLIB`（现有 Kotlin stdlib jar），在源码根运行：

```powershell
node operit/native/build-helper.mjs
node --test tests/*.test.mjs
```

脚本编译并运行原生测试，生成 DEX jar，然后更新插件内嵌字节、文件名与校验值。普通安装直接导入配套 JS，无须编译 helper。
