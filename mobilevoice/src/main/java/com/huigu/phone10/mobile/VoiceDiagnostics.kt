package com.huigu.phone10.mobile

/** Bounded local events. No speech, request bodies or configuration is stored. */
object VoiceDiagnostics {
    private val events = ArrayDeque<String>()
    private val failures = ArrayDeque<String>()
    private val address = Regex("(?i)(?:https?|wss?)://\\S+")
    private val credential = Regex("(?i)Bearer\\s+\\S+|sk-[A-Za-z0-9_-]+")
    private val failedStatus = Regex("status_[45]\\d\\d")
    private val timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX")
    @Synchronized fun record(event: String) {
        val safe = event.replace(address, "[地址已隐藏]")
            .replace(credential, "[密钥已隐藏]")
            .replace('\n', ' ').replace('\r', ' ').take(480)
        val time = java.time.ZonedDateTime.now().format(timestamp)
        val line = "$time · ${System.nanoTime() / 1_000_000} ms · $safe"
        events.addLast(line)
        while (events.size > 240) events.removeFirst()
        if ("failed" in safe || "error" in safe || "timeout" in safe || "失败" in safe || failedStatus.containsMatchIn(safe)) {
            failures.addLast(line)
            while (failures.size > 24) failures.removeFirst()
        }
    }
    @Synchronized fun snapshot(): String = "最近失败（最多24条）\n" + failures.joinToString("\n").ifBlank { "暂无" } +
        "\n\n最近事件（最多240条）\n" + events.joinToString("\n").ifBlank { "暂无" }
}

data class Phone10MicrophoneState(val enabled: Boolean = false, val changing: Boolean = false,
    val error: String? = null, val pendingReview: Boolean = false)
