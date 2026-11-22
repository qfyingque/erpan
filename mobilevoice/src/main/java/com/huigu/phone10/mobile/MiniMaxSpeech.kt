package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import okhttp3.*
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Official bidi protocol; one reply owns one task/socket. Never retry submitted text. */
internal class MiniMaxSpeech(private val config: SpeechConfig, private val sockets: WebSocket.Factory,
    private val timeoutMillis: Long = 600_000) {
    suspend fun speakStream(texts: ReceiveChannel<String>, onPcm: (ByteArray) -> Unit) {
        try {
            val initial = texts.receiveCatching()
            initial.exceptionOrNull()?.let { throw it }
            val first = initial.getOrNull() ?: return
            withTimeoutOrNull(timeoutMillis) {
                withContext(Dispatchers.IO) { exchange(first, texts, onPcm) }
                true
            } ?: throw SpeechApiException("MiniMax 合成超时。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw if (error is SpeechApiException) error else SpeechApiException("MiniMax 语音响应无效。")
        }
    }

    private data class Incoming(val text: String, val receivedMillis: Long)

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun exchange(first: String, texts: ReceiveChannel<String>, onPcm: (ByteArray) -> Unit) = coroutineScope {
        val events = Channel<Incoming>(16)
        val listener = object : WebSocketListener() {
            private fun fail() { events.close(SpeechApiException("MiniMax 语音连接中断。")) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 4_194_304) { fail(); webSocket.cancel(); return }
                val incoming = Incoming(text, System.nanoTime() / 1_000_000)
                try { runBlocking { events.send(incoming) } } catch (_: Exception) { /* canceled operation */ }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { fail(); webSocket.cancel() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { response?.close(); fail() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = fail()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail()
        }
        var socket: WebSocket? = null
        try {
            val ws = sockets.newWebSocket(Request.Builder().url(config.minimaxEndpoint(config.ttsBaseUrl))
                .header("Authorization", "Bearer ${config.ttsKey}").build(), listener).also { socket = it }
            fun send(event: String, configure: JsonObject.() -> Unit = {}) {
                val json = JsonObject().apply { addProperty("event", event); configure() }
                if (!ws.send(json.toString())) throw SpeechApiException("MiniMax 语音发送失败。")
            }
            var connected = false
            var started = false
            val inputFinished = AtomicBoolean(false)
            val sentChunks = AtomicInteger()
            val sentCharacters = AtomicInteger()
            val firstSentMillis = AtomicLong()
            val lastSentMillis = AtomicLong()
            var carry: Byte? = null
            var total = 0L
            var lastTrace = "none"
            fun providerFailure(code: Int, status: String, receivedMillis: Long): Nothing {
                val handled = System.nanoTime() / 1_000_000
                val detail = when {
                    Regex("(?i)\\brpm\\b").containsMatchIn(status) -> "rpm_limit"
                    Regex("(?i)\\btpm\\b").containsMatchIn(status) -> "tpm_limit"
                    Regex("(?i)\\b(qps|rps)\\b").containsMatchIn(status) -> "request_rate"
                    status.contains("concurr", true) -> "concurrency"
                    code == 1002 -> "rate_limit"
                    code == 2205 -> "synthesis_queue"
                    code == 1008 -> "balance"
                    else -> "provider_error"
                }
                // Provider messages may echo credentials or text. Keep categories, not raw messages.
                VoiceDiagnostics.record("minimax_error_code_${code}_detail_${detail}_trace_${lastTrace}" +
                    "_chunks_${sentChunks.get()}_chars_${sentCharacters.get()}_pcm_bytes_${total}" +
                    "_receive_ms_${receivedMillis}_handled_ms_${handled}_queue_wait_ms_${handled - receivedMillis}")
                val reason = when (code) {
                    1002 -> "请求暂时受限"
                    2205 -> "待合成队列已满"
                    1008 -> "账户余额不足"
                    else -> "合成失败"
                }
                throw SpeechApiException("MiniMax $reason（状态码 $code，$detail，trace=$lastTrace）。")
            }
            for (incoming in events) {
                ensureActive()
                val message = JsonParser.parseString(incoming.text).asJsonObject
                val trace = message["trace_id"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
                if (trace.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
                    !trace.startsWith("sk-", true) && (config.ttsKey.isEmpty() || !trace.contains(config.ttsKey))) lastTrace = trace
                val code = message.getAsJsonObject("base_resp")?.get("status_code")?.asInt ?: 0
                val status = message.getAsJsonObject("base_resp")?.get("status_msg")?.asString.orEmpty()
                if (code != 0) providerFailure(code, status, incoming.receivedMillis)
                val event = message["event"]?.asString
                when (event) {
                    "connected_success" -> {
                        check(!connected); connected = true
                        send("task_start") {
                            addProperty("model", config.ttsModel)
                            add("voice_setting", config.miniMaxVoiceJson())
                            add("audio_setting", JsonObject().apply {
                                addProperty("sample_rate", 24_000); addProperty("format", "pcm"); addProperty("channel", 1)
                            })
                        }
                    }
                    "task_started" -> {
                        check(connected && !started); started = true
                        launch {
                            var characters = 0
                            var highSurrogate: Char? = null
                            suspend fun input(text: String) {
                                require(text.isNotEmpty())
                                characters += text.length
                                require(characters <= 60_000)
                                val combined = highSurrogate?.let { it + text } ?: text
                                highSurrogate = combined.lastOrNull()?.takeIf { it.isHighSurrogate() }
                                val content = if (highSurrogate != null) combined.dropLast(1) else combined
                                // Protocol permits at most 10,000 characters per continue. Preserve
                                // surrogate pairs; server, not this boundary, decides speech sentences.
                                var begin = 0
                                while (begin < content.length) {
                                    ensureActive()
                                    var end = minOf(begin + 8_000, content.length)
                                    if (end < content.length && content[end - 1].isHighSurrogate()) end--
                                    while (ws.queueSize() > 262_144) { delay(10); ensureActive() }
                                    val slice = content.substring(begin, end)
                                    send("task_continue") { addProperty("text", slice) }
                                    sentChunks.incrementAndGet()
                                    sentCharacters.addAndGet(slice.length)
                                    val now = System.nanoTime() / 1_000_000
                                    firstSentMillis.compareAndSet(0, now)
                                    lastSentMillis.set(now)
                                    begin = end
                                }
                            }
                            // First audio is never delayed to fill a batch.
                            input(first)
                            var pending: String? = null
                            var closed = false
                            fun unpack(result: ChannelResult<String>): String? {
                                result.exceptionOrNull()?.let { throw it }
                                if (result.isClosed) closed = true
                                return result.getOrNull()?.also { require(it.isNotEmpty()) }
                            }
                            while (!closed || pending != null) {
                                ensureActive()
                                val head = pending?.also { pending = null } ?: unpack(texts.receiveCatching()) ?: break
                                val batch = StringBuilder()
                                fun append(value: String) {
                                    val count = minOf(8_000 - batch.length, value.length)
                                    batch.append(value, 0, count)
                                    if (count < value.length) pending = value.substring(count)
                                }
                                append(head)
                                val deadline = System.nanoTime() + 150_000_000L
                                while (pending == null && !closed && batch.length < 8_000) {
                                    ensureActive()
                                    val available = texts.tryReceive()
                                    if (available.isSuccess || available.isClosed) {
                                        unpack(available)?.let(::append)
                                        continue
                                    }
                                    if (batch.length >= 300) break
                                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                                    if (remaining <= 0) break
                                    // select owns both alternatives; a timeout cannot consume and discard a fragment.
                                    val next = select<ChannelResult<String>?> {
                                        texts.onReceiveCatching { it }
                                        onTimeout(remaining) { null }
                                    } ?: break
                                    unpack(next)?.let(::append)
                                }
                                input(batch.toString())
                            }
                            check(highSurrogate == null) { "MiniMax 文字含不完整字符。" }
                            ensureActive()
                            inputFinished.set(true)
                            VoiceDiagnostics.record("minimax_input_done_chunks_${sentChunks.get()}_chars_${sentCharacters.get()}" +
                                "_send_ms_${lastSentMillis.get() - firstSentMillis.get()}")
                            send("task_finish")
                        }
                    }
                    "task_failed" -> providerFailure(code, status, incoming.receivedMillis)
                    "task_finished" -> {
                        check(started && inputFinished.get())
                        if (total == 0L || carry != null) throw SpeechApiException("MiniMax 音频为空或 PCM 不完整。")
                        VoiceDiagnostics.record("minimax_task_finished_chunks_${sentChunks.get()}_chars_${sentCharacters.get()}_pcm_bytes_$total")
                        return@coroutineScope
                    }
                    "task_continued", "sentence_start", "sentence_end", null -> check(started)
                    else -> throw SpeechApiException("MiniMax 返回不兼容的语音事件。")
                }
                message.getAsJsonObject("extra_info")?.let { info ->
                    info["audio_sample_rate"]?.let { check(it.asInt == 24_000) }
                    info["audio_channel"]?.let { check(it.asInt == 1) }
                    info["audio_format"]?.let { check(it.asString == "pcm") }
                }
                val hex = message.getAsJsonObject("data")?.get("audio")?.asString.orEmpty()
                if (hex.isNotEmpty()) {
                    check(started)
                    val bytes = decodeHex(hex)
                    total += bytes.size
                    check(total <= 28_800_000)
                    val aligned = carry?.let { byteArrayOf(it) + bytes } ?: bytes
                    val size = aligned.size and -2
                    carry = if (size == aligned.size) null else aligned.last()
                    if (size > 0) runInterruptible { onPcm(aligned.copyOf(size)) }
                }
                // is_final ends one audio request; sentence_end ends one sentence. Neither closes this task.
            }
            throw SpeechApiException("MiniMax 未返回完整的任务结束事件。")
        } finally { events.cancel(); socket?.cancel() }
    }

    companion object {
        internal fun decodeHex(value: String): ByteArray {
            require(value.length % 2 == 0)
            return ByteArray(value.length / 2) { index ->
                val high = value[index * 2].digitToIntOrNull(16) ?: error("Invalid hex")
                val low = value[index * 2 + 1].digitToIntOrNull(16) ?: error("Invalid hex")
                (high * 16 + low).toByte()
            }
        }
    }
}
