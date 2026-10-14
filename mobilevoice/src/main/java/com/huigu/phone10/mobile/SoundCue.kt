package com.huigu.phone10.mobile

enum class SoundCue(val label: String) {
    BREATH("呼吸或吸气声"), SIGH("叹气声");
    fun message() = "[耳畔声音线索：麦克风检测到疑似${label}，为自动估计，未识别为说话内容。]"
}

internal class SoundCueGate {
    private var previousBreath = false
    private var previousSigh = false
    private var lastWindow: Long? = null
    private var lastSent: Long? = null
    private var latched = false
    private var quiet = 0

    fun accept(atMillis: Long, breath: Float, sigh: Float): SoundCue? {
        val distance = lastWindow?.let { atMillis - it }
        if (distance != null && distance !in 1..750) {
            previousBreath = false
            previousSigh = false
        }
        lastWindow = atMillis
        val valid = breath.isFinite() && sigh.isFinite()
        val hasBreath = valid && breath >= 0.3f
        val hasSigh = valid && sigh >= 0.5f
        // Confirm each class independently: overlapping labels may swap rank.
        val confirmedBreath = hasBreath && previousBreath
        val confirmedSigh = hasSigh && previousSigh
        previousBreath = hasBreath
        previousSigh = hasSigh
        val cue = when {
            confirmedSigh && (!confirmedBreath || sigh > breath) -> SoundCue.SIGH
            confirmedBreath -> SoundCue.BREATH
            else -> null
        }
        if (!hasBreath && !hasSigh) {
            quiet++
            if (quiet >= 3) latched = false
            return null
        }
        quiet = 0
        if (cue == null || latched || lastSent?.let { atMillis - it < 8000 } == true) return null
        latched = true
        lastSent = atMillis
        return cue
    }
    // A discontinuity clears evidence, but never erases the anti-spam cooldown.
    fun reset() { previousBreath = false; previousSigh = false; lastWindow = null; quiet = 0; latched = false }
}

internal class SoundCueWindows(private val onWindow: (FloatArray) -> Unit) {
    private val samples = FloatArray(15600)
    private var used = 0
    private var lowByte: Int? = null
    fun accept(bytes: ByteArray) {
        for (byte in bytes) {
            val low = lowByte
            if (low == null) { lowByte = byte.toInt() and 255; continue }
            samples[used++] = ((byte.toInt() shl 8) or low).toShort() / 32768f
            lowByte = null
            if (used == samples.size) {
                onWindow(samples.copyOf())
                samples.copyInto(samples, 0, 7680)
                used -= 7680
            }
        }
    }
    fun reset() { used = 0; lowByte = null }
}
