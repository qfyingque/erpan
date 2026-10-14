package com.huigu.phone10.mobile

import com.google.gson.Gson
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

internal data class VoiceClipSegment(val text: String, val start: Long, val end: Long, val complete: Boolean)
internal data class VoiceClip(val id: String, val text: String, val voice: String, val rate: Int,
    val frames: Long, val complete: Boolean, val created: Long, val segments: List<VoiceClipSegment>,
    val sourceId: String? = null, val sourceSegment: Int? = null)

/** App-private PCM history. Only an explicit save copies audio into the permanent collection. */
internal class VoiceKeepsakes(private val recentDir: File, private val savedDir: File) {
    private val gson = Gson()
    init { recentDir.mkdirs(); savedDir.mkdirs() }
    private fun file(dir: File, id: String, suffix: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "声音编号无效" }
        return File(dir, "$id.$suffix")
    }
    fun audio(id: String, saved: Boolean): File = file(if (saved) savedDir else recentDir, id, "wav")
    private fun read(dir: File): List<VoiceClip> = dir.listFiles().orEmpty().filter { it.extension == "json" }
        .mapNotNull { runCatching {
            val clip = gson.fromJson(it.readText(), VoiceClip::class.java)
            require(clip.rate in 8000..48000 && clip.frames > 0)
            require(file(dir,clip.id,"wav").length() == 44 + clip.frames * 2)
            clip
        }.getOrNull() }.sortedByDescending { it.created }
    fun recent(): List<VoiceClip> = synchronized(lock) { read(recentDir) }
    fun saved(): List<VoiceClip> = synchronized(lock) { read(savedDir) }
    fun saved(id: String): VoiceClip = saved().firstOrNull { it.id == id } ?: error("收藏不存在")
    fun clip(id: String, saved: Boolean): VoiceClip = (if (saved) saved() else recent())
        .firstOrNull { it.id == id } ?: error("声音不存在或已过期")
    fun openAudio(id: String, saved: Boolean): RandomAccessFile = synchronized(lock) {
        val clip=clip(id,saved)
        RandomAccessFile(audio(id,saved),"r").also { input ->
            try {
                val header=ByteArray(44); input.readFully(header)
                check(header.contentEquals(wavHeader(clip.rate,clip.frames*2)))
            } catch (error: Exception) { input.close(); throw error }
        }
    }
    fun begin(voice: String): Capture = synchronized(lock) {
        val retained=read(recentDir).map { it.id }.toSet()
        recentDir.listFiles().orEmpty().filter {
            it.extension in setOf("pending","wav","json") && it.nameWithoutExtension !in activeCaptures &&
                it.nameWithoutExtension !in retained
        }.forEach { it.delete() }
        val id=UUID.randomUUID().toString()
        Capture(this,id,voice).also { activeCaptures.add(id) }
    }
    private fun publish(dir: File, clip: VoiceClip) {
        file(dir,clip.id,"json").writeText(gson.toJson(clip))
    }
    fun save(id: String, segmentIndex: Int? = null): VoiceClip = synchronized(lock) {
        read(savedDir).firstOrNull { it.sourceId == id && it.sourceSegment == segmentIndex }?.let { return@synchronized it }
        val original = read(recentDir).firstOrNull { it.id == id } ?: error("临时声音已过期，请重新选择")
        val part = segmentIndex?.let { original.segments.getOrNull(it) ?: error("声音段落不存在") }
        val start = part?.start ?: 0
        val end = part?.end ?: original.frames
        require(start >= 0 && end > start && end <= original.frames)
        val targetId = UUID.randomUUID().toString()
        val target = audio(targetId,true)
        try { RandomAccessFile(audio(id,false),"r").use { input ->
            RandomAccessFile(target,"rw").use { out ->
                out.write(wavHeader(original.rate, (end-start)*2))
                input.seek(44 + start*2)
                val buffer = ByteArray(8192)
                var left = (end-start)*2
                while (left > 0) {
                    val n = input.read(buffer,0,minOf(left,buffer.size.toLong()).toInt())
                    check(n > 0); out.write(buffer,0,n); left -= n
                }
            }
        }
        val clip = VoiceClip(targetId,part?.text ?: original.text,original.voice,original.rate,
            end-start,part?.complete ?: original.complete,System.currentTimeMillis(),emptyList(),id,segmentIndex)
        publish(savedDir,clip)
        clip
        } catch (error: Exception) { target.delete(); file(savedDir,targetId,"json").delete(); throw error }
    }
    private fun trimRecent() {
        var total = recentDir.listFiles().orEmpty().sumOf { it.length() }
        // Only finalized disposable cache entries; permanent favorites are never evicted.
        val clips = read(recentDir)
        var count = clips.size
        clips.asReversed().forEach { clip ->
            if (count > 24 || total > 128L*1024*1024) {
                for (ext in listOf("wav","json")) {
                    val f=file(recentDir,clip.id,ext); val bytes=f.length()
                    if (f.delete()) total-=bytes
                }
                count--
            }
        }
    }
    class Capture internal constructor(private val store: VoiceKeepsakes, private val id: String, private val voice: String) : PcmCaptureObserver {
        private val output = RandomAccessFile(store.file(store.recentDir,id,"pending"),"rw")
        private var rate = 24_000
        private var bytes = 0L
        private var failed = false
        private var limited = false
        private var ended = false
        private val marks = mutableListOf<Pair<Long,String>>()
        override fun configure(sampleRate: Int) { rate=sampleRate }
        override fun segment(text: String) { if (!failed && !limited && !ended) marks.add(bytes/2 to text) }
        override fun accept(data: ByteArray, offset: Int, count: Int) {
            if (failed || limited || ended) return
            try {
                val accepted=minOf(count.toLong(),48L*1024*1024-bytes).toInt()
                output.write(data,offset,accepted);bytes+=accepted
                if (accepted<count) limited=true
            } catch (_: Exception) { failed=true }
        }
        fun finish(playedFrames: Long, complete: Boolean, text: String): VoiceClip? = synchronized(lock) {
            if (ended) return@synchronized null
            ended=true
            val pending=store.file(store.recentDir,id,"pending")
            try {
                output.close()
                val frames=minOf(playedFrames.coerceAtLeast(0),bytes/2)
                val full=complete && !limited && frames==bytes/2
                if (failed || frames == 0L) return@synchronized null
                val target=store.audio(id,false)
                RandomAccessFile(pending,"r").use { input ->
                    RandomAccessFile(target,"rw").use { out ->
                        out.write(wavHeader(rate,frames*2))
                        val buffer=ByteArray(8192)
                        var remaining=frames*2
                        while (remaining>0) {
                            val n=input.read(buffer,0,minOf(remaining,buffer.size.toLong()).toInt())
                            check(n>0);out.write(buffer,0,n);remaining-=n
                        }
                    }
                }
                val segments=marks.mapIndexedNotNull { index, (start, words) ->
                    val boundary=marks.getOrNull(index+1)?.first ?: (bytes/2)
                    val end=minOf(boundary,frames)
                    if (end<=start) null else VoiceClipSegment(words,start,end,
                        boundary<=frames && (index<marks.lastIndex || full))
                }
                val clip=VoiceClip(id,segments.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }
                    ?: text,voice,rate,frames,full,System.currentTimeMillis(),segments)
                store.publish(store.recentDir,clip);store.trimRecent();clip
            } finally { runCatching { output.close() }; pending.delete(); activeCaptures.remove(id) }
        }
    }
    companion object { private val lock=Any(); private val activeCaptures=mutableSetOf<String>() }
}

internal interface PcmCaptureObserver {
    fun configure(sampleRate: Int)
    fun segment(text: String)
    fun accept(data: ByteArray, offset: Int, count: Int)
}

internal fun wavHeader(rate: Int, bytes: Long): ByteArray {
    require(rate in 8000..48000 && bytes in 0..Int.MAX_VALUE.toLong() && bytes%2==0L)
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray());putInt(36+bytes.toInt());put("WAVEfmt ".toByteArray())
        putInt(16);putShort(1);putShort(1);putInt(rate);putInt(rate*2);putShort(2);putShort(16)
        put("data".toByteArray());putInt(bytes.toInt())
    }.array()
}
