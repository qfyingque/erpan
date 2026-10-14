# Third-party notices

- Android / AndroidX / Compose: Apache-2.0. Used through Android and Gradle APIs.
- Kotlin and kotlinx.coroutines: Apache-2.0.
- OkHttp 5.2.1: Apache-2.0; https://github.com/square/okhttp
- MockWebServer 5.2.1: Apache-2.0, from the same OkHttp project; test-only, not shipped in the APK.
- Gson 2.13.2: Apache-2.0; https://github.com/google/gson
- Coil and coil-gif 2.7.0: Apache-2.0; https://github.com/coil-kt/coil . Used to decode still and animated avatar images.
- android-vad Silero 2.0.10: MIT; https://github.com/gkonovalov/android-vad . Its ONNX Runtime and model dependencies retain their own bundled licenses (ONNX Runtime MIT, Silero VAD MIT). Preserve their license files when redistributing binaries.
- Android Gradle scaffold derives from the existing Phone10 Android sample; retain the repository's LiveKit sample MIT notice. This standalone module has no LiveKit SDK dependency.
- Operit API integration was checked against Operit 1.12.2. No Operit source is vendored. Users install Operit separately; its own license and terms remain independent.

Protocol references: https://developers.openai.com/api/docs/guides/text-to-speech and https://developers.openai.com/api/docs/guides/transcription . Protocol compatibility does not imply that a provider supports every model or voice.

Bailian native protocol references: https://help.aliyun.com/zh/model-studio/cosyvoice-websocket-api and https://help.aliyun.com/zh/model-studio/websocket-for-paraformer-real-time-service . No Alibaba SDK or documentation source is bundled in the application.

License texts and provenance for redistribution are in licenses/. This is a component notice, not a license grant for hosted models or voices.

- YAMNet audio event model: Google / TensorFlow, Apache-2.0; https://github.com/tensorflow/models/tree/master/research/audioset/yamnet . ONNX conversion by zeropointnine, https://huggingface.co/zeropointnine/yamnet-onnx at ac2ca3bd45d12ec1f19f1144205ea529b4e9dedf. Historical model and class-map provenance: licenses/YAMNet-SOURCES.json; license: licenses/Apache-2.0.txt. The retired YAMNet model and class map are not included in code69 source archives or APKs. Ordinary call voice activity detection retains Silero and ONNX Runtime. Training data and private/reference test recordings are not distributed.
