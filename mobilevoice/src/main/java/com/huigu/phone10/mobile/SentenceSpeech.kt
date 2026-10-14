package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** One reply's ordered HTTP speech output. */
internal class SentenceSpeech(
    private val synthesize: suspend (String, (Int) -> Unit, (ByteArray) -> Unit) -> Unit,
) {
    private sealed interface Packet {
        data class Segment(val text: String) : Packet
        data class Format(val rate: Int) : Packet
        data class Audio(val pcm: ByteArray) : Packet
    }

    suspend fun speak(texts: ReceiveChannel<String>, onRate: (Int) -> Unit,
                      onPcm: suspend (ByteArray) -> Unit): Unit = speak(texts, onRate, {}, onPcm)

    suspend fun speak(texts: ReceiveChannel<String>, onRate: (Int) -> Unit,
                      onSegment: (String) -> Unit,
                      onPcm: suspend (ByteArray) -> Unit): Unit = coroutineScope {
        // Rendezvous hand-off limits lookahead to the current and next sentence.
        // Only one HTTP request is active at a time. Each packet queue holds at
        // most 256 KiB of PCM, applying backpressure to the synchronous callback.
        val clips = Channel<Channel<Packet>>(Channel.RENDEZVOUS)
        val producer = launch {
            try {
                for (text in texts) {
                    val packets = Channel<Packet>(32)
                    try {
                        val synthesis = async(Dispatchers.IO) {
                            val synthesisJob = currentCoroutineContext().job
                            var failure: Exception? = null
                            var audioBytes=0L
                            fun send(packet: Packet) = runBlocking(synthesisJob) { packets.send(packet) }
                            try {
                                send(Packet.Segment(text))
                                synthesize(text, { send(Packet.Format(it)) }, { pcm ->
                                    audioBytes+=pcm.size
                                    var offset = 0
                                    while (offset < pcm.size) {
                                        val end = minOf(offset + 8192, pcm.size)
                                        send(Packet.Audio(pcm.copyOfRange(offset, end)))
                                        offset = end
                                    }
                                })
                                if (audioBytes==0L || audioBytes%2!=0L)
                                    throw SpeechApiException("本段音频为空或不完整，朗读已停止，请查看文字。")
                                VoiceDiagnostics.record("segment_audio_complete_bytes_$audioBytes")
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                // Deliver failure in audio order. A speculative next
                                // request must not cancel already queued current audio.
                                failure = error
                            } finally { packets.close(failure) }
                            failure
                        }
                        clips.send(packets)
                        if (synthesis.await() != null) break
                    } catch (error: Throwable) {
                        packets.cancel()
                        throw error
                    }
                }
            } finally { clips.close() }
        }
        var sampleRate: Int? = null
        try {
            for (packets in clips) {
                try {
                    for (packet in packets) when (packet) {
                        is Packet.Segment -> { currentCoroutineContext().ensureActive(); onSegment(packet.text) }
                        is Packet.Format -> {
                            require(packet.rate in 8000..48000) { "不支持的音频采样率。" }
                            if (sampleRate == null) { onRate(packet.rate); sampleRate = packet.rate }
                            else check(sampleRate == packet.rate) { "同一回复的音频格式发生变化。" }
                        }
                        is Packet.Audio -> {
                            check(sampleRate != null) { "缺少音频格式。" }
                            currentCoroutineContext().ensureActive()
                            onPcm(packet.pcm)
                        }
                    }
                } finally { packets.cancel() }
            }
            producer.join()
        } finally { producer.cancel(); clips.cancel() }
    }
}
