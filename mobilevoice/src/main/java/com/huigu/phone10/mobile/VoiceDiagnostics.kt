package com.huigu.phone10.mobile

/** Only bounded event labels and elapsed times. Never record speech, text, keys or addresses. */
object VoiceDiagnostics {
    private val events = ArrayDeque<String>()
    @Synchronized fun record(event: String) {
        events.addLast("${System.nanoTime() / 1_000_000} ms · ${event.take(100)}")
        while (events.size > 40) events.removeFirst()
    }
    @Synchronized fun snapshot(): String = events.joinToString("\n")
}

data class Phone10MicrophoneState(val enabled: Boolean = false, val changing: Boolean = false,
    val error: String? = null)
