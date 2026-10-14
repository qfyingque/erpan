package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

internal data class ObservedReply(val timestamp: Long, val text: String, val finished: Boolean = true, val roleName: String = "")
internal data class ReplySnapshot(val cursor: Long, val replies: List<ObservedReply>, val processing: Boolean, val failed: Boolean = false,
                                  val notice: String? = null, val roleNames: List<String> = emptyList(), val roleMetadata: Boolean = false)

/** Main-dispatcher owner of read-only polling and bounded, transient speech. */
internal class ReplyListener(
    private val read: suspend (Long?) -> ReplySnapshot,
    private val play: (ReceiveChannel<String>) -> Job,
    private val stopPlayback: () -> Unit,
    private val report: (String) -> Unit,
    private val intervalMillis: Long = 500,
    private val diagnose: (String) -> Unit = {},
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val playRole: ((String, ReceiveChannel<String>) -> Job)? = null,
    private val initialSnapshot: ReplySnapshot? = null,
    private val finishWhenIdle: (() -> Boolean)? = null,
) {
    private class Utterance(val timestamp: Long, val trace: Long, val enqueuedAt: Long, val roleName: String) {
        val chunks = Channel<String>(Channel.UNLIMITED)
        var text = ""
        var finished = false
        var consumed = false
    }
    private val queue = Channel<Utterance>(16)
    private val utterances = linkedMapOf<Long, Utterance>()
    private val suppressed = mutableSetOf<Long>()
    private var playing: Job? = null
    private var playingUtterance: Utterance? = null
    private var processing = false
    private var reading = false
    private var suppressPending = false
    private var revision = 0L
    private var traceSequence = 0L

    suspend fun run(): Unit = coroutineScope {
        val baseline = initialSnapshot ?: read(null)
        var cursor = baseline.cursor // Opening never reads historical conversation aloud.
        processing = baseline.processing
        suppressPending = processing
        diagnose("listen_baseline_processing_${baseline.processing}")
        report("只听回复已开启 · 请在 Operit 所选聊天中打字")
        val speaker = launch {
            for (reply in queue) {
                if (reply.timestamp in suppressed || reply.consumed) continue
                diagnose("listen_dequeued_${reply.trace}_wait_ms_${nowMillis() - reply.enqueuedAt}")
                playingUtterance = reply
                val job = (playRole?.invoke(reply.roleName, reply.chunks) ?: play(reply.chunks)).also { playing = it }
                try { job.join() } finally {
                    diagnose("listen_speaker_released_${reply.trace}")
                    reply.consumed = true
                    reply.chunks.cancel()
                    if (playing === job) { playing = null; playingUtterance = null }
                }
            }
        }
        var previousReadEnded = nowMillis()
        var lastPollTraceAt: Long? = null
        var pollCount = 0L
        try {
            while (isActive) {
                delay(intervalMillis)
                val beforeRead = revision
                reading = true
                val readStarted = nowMillis()
                val pollGap = readStarted - previousReadEnded
                if (pollGap >= 2000) diagnose("listen_poll_gap_ms_$pollGap")
                pollCount++
                val tracePoll = lastPollTraceAt?.let { readStarted - it >= 10_000 } ?: true
                if (tracePoll) {
                    lastPollTraceAt = readStarted
                    diagnose("listen_poll_begin_$pollCount")
                }
                val finishedBeforeRead = finishWhenIdle?.invoke() == true
                val snapshot = try { read(cursor) } finally { reading = false }
                previousReadEnded = nowMillis()
                val readMillis = previousReadEnded - readStarted
                if (readMillis >= 1500) diagnose("listen_slow_read_ms_$readMillis")
                if (tracePoll) diagnose("listen_poll_end_${pollCount}_replies_${snapshot.replies.size}_processing_${snapshot.processing}_advanced_${snapshot.cursor > cursor}")
                check(snapshot.cursor >= cursor) { "OPERIT_INVALID_EVENT" }
                when (snapshot.notice) {
                    "HISTORY_GAP" -> {
                        diagnose("reply_listener_history_gap")
                        report("聊天进度已超出可读取范围，已从仍可读取的回复继续；较早内容请在 Operit 查看。")
                    }
                    "MESSAGE_TOO_LARGE" -> {
                        diagnose("reply_listener_message_too_large")
                        utterances.values.filter { it.timestamp <= snapshot.cursor && !it.finished }.toList().forEach(::discard)
                        report("这条回复超过单条读取上限，已跳过；只听回复仍开启，后续回复会继续朗读。")
                    }
                }
                if (snapshot.failed) utterances.values.filter { !it.finished }.toList().forEach(::discard)
                for (reply in snapshot.replies) {
                    if (reply.timestamp <= cursor) continue
                    if (beforeRead != revision || suppressPending || snapshot.failed) {
                        if (suppressed.add(reply.timestamp)) diagnose("listen_skipped_pending_reply")
                    }
                    if (reply.timestamp in suppressed) continue
                    check(!reply.finished || reply.timestamp <= snapshot.cursor) { "OPERIT_INVALID_EVENT" }
                    // A later message proves the preceding snapshot's message ended,
                    // even when a second O turn starts between polling ticks.
                    utterances.values.filter { it.timestamp < reply.timestamp && !it.finished }.forEach {
                        diagnose("listen_source_closed_${it.trace}_by_next")
                        it.finished = true; it.chunks.close()
                    }
                    val text = visibleText(reply.text)
                    if (text.length > MAX_MESSAGE_CHARS) {
                        utterances[reply.timestamp]?.let(::discard)
                        suppressed += reply.timestamp
                        diagnose("reply_listener_message_too_large")
                        report("这条回复超过单条读取上限，已跳过；后续回复会继续朗读。")
                        continue
                    }
                    var utterance = utterances[reply.timestamp]
                    if (utterance == null) {
                        if (text.isEmpty() && reply.finished) continue
                        utterance = Utterance(reply.timestamp, ++traceSequence, nowMillis(), reply.roleName)
                        utterances[reply.timestamp] = utterance
                        diagnose("listen_enqueued_${utterance.trace}_chars_${text.length}")
                        if (!queue.trySend(utterance).isSuccess) {
                            diagnose("reply_listener_backpressure_queue")
                            report("待播回复较多，正在按顺序朗读…")
                            queue.send(utterance)
                        }
                        // Stop may have happened while queue.send was suspended.
                        if (beforeRead != revision || utterance.consumed) { discard(utterance); continue }
                    }
                    if (reply.roleName != utterance.roleName) {
                        discard(utterance)
                        report("回复的说话人发生变化，本条已停止朗读。")
                        continue
                    }
                    if (!text.startsWith(utterance.text)) {
                        discard(utterance)
                        report("Operit 改写了正在播放的正文，本条已停止朗读，请查看文字。")
                        continue
                    }
                    var waiting = false
                    while (utterances.values.filter { !it.consumed && it !== utterance }.sumOf { it.text.length } + text.length > MAX_PENDING_CHARS) {
                        if (!waiting) {
                            diagnose("reply_listener_backpressure_chars")
                            report("长回复正在排队朗读…")
                            waiting = true
                        }
                        delay(50)
                        if (beforeRead != revision || utterance.consumed) break
                    }
                    if (beforeRead != revision || utterance.consumed) { discard(utterance); continue }
                    val delta = text.substring(utterance.text.length)
                    if (delta.isNotEmpty() && !utterance.consumed) check(utterance.chunks.trySend(delta).isSuccess) { "OPERIT_INVALID_EVENT" }
                    utterance.text = text
                    if (reply.finished) {
                        if (!utterance.finished) diagnose("listen_source_closed_${utterance.trace}_by_snapshot")
                        utterance.finished = true; utterance.chunks.close()
                    }
                }
                if (!snapshot.processing) {
                    utterances.values.filter { !it.finished }.toList().forEach(::discard)
                    suppressPending = false
                }
                processing = snapshot.processing && !snapshot.failed
                cursor = snapshot.cursor
                suppressed.removeAll { it <= cursor }
                utterances.entries.removeAll { it.value.consumed && it.key <= cursor }
                if (finishedBeforeRead && finishWhenIdle?.invoke() == true && !snapshot.processing &&
                    snapshot.replies.isEmpty() && utterances.values.all { it.consumed }) break
            }
        } finally { interrupt(); queue.cancel(); speaker.cancelAndJoin() }
    }

    fun interrupt() {
        revision++
        suppressPending = processing || reading
        utterances.values.toList().forEach(::discard)
        while (queue.tryReceive().isSuccess) { /* Drop queued sound; never touch O's turn. */ }
        playing?.cancel()
        stopPlayback()
    }

    private fun discard(utterance: Utterance) {
        suppressed += utterance.timestamp
        utterance.consumed = true
        utterance.chunks.cancel()
        if (playingUtterance === utterance) { playing?.cancel(); stopPlayback() }
    }

    companion object {
        const val MAX_MESSAGE_CHARS = 512_000
        private const val MAX_PENDING_CHARS = 768_000
        // Normalize both O's partial snapshots and its XML-simplified final history
        // identically. Incomplete/hidden tags never become speech or a replay offset.
        internal fun visibleText(text: String): String {
            val filter = SpeechText(preserveSpacing = true)
            return (filter.push(text) + filter.flush()).joinToString("").replace(Regex("\\s+"), " ").trim()
        }
    }
}
