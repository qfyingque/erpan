# Third-party notices

- Android / AndroidX / Compose: Apache-2.0. Used through Android and Gradle APIs.
- Kotlin and kotlinx.coroutines: Apache-2.0.
- OkHttp 5.2.1: Apache-2.0; https://github.com/square/okhttp
- MockWebServer 5.2.1: Apache-2.0, from the same OkHttp project; test-only, not shipped in the APK.
- Gson 2.13.2: Apache-2.0; https://github.com/google/gson
- android-vad Silero 2.0.10: MIT; https://github.com/gkonovalov/android-vad . Its ONNX Runtime and model dependencies retain their own bundled licenses (ONNX Runtime MIT, Silero VAD MIT). Preserve their license files when redistributing binaries.
- Android Gradle scaffold derives from the existing Phone10 Android sample; retain the repository's LiveKit sample MIT notice. This standalone module has no LiveKit SDK dependency.
- Operit API integration targets local source audit revision 1ed8687. No Operit source is vendored. Users install Operit separately; its own license and terms remain independent.

Protocol references: https://developers.openai.com/api/docs/guides/text-to-speech and https://developers.openai.com/api/docs/guides/transcription . Protocol compatibility does not imply that a provider supports every model or voice.

Bailian native protocol references: https://help.aliyun.com/zh/model-studio/cosyvoice-websocket-api and https://help.aliyun.com/zh/model-studio/websocket-for-paraformer-real-time-service . No Alibaba SDK or documentation source is bundled in the application.
