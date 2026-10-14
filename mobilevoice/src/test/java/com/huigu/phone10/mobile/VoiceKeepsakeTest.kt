package com.huigu.phone10.mobile

import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class VoiceKeepsakeTest {
    @Test fun cacheEvictsOldestButKeepsExplicitlySavedCopyAndActiveCapture() {
        val root=Files.createTempDirectory("voice-cache").toFile()
        val store=VoiceKeepsakes(root.resolve("recent"),root.resolve("saved"))
        fun add():VoiceClip {
            val capture=store.begin("test")
            capture.accept(byteArrayOf(1,2),0,2)
            return capture.finish(1,true,"一句话")!!
        }
        val first=add();val saved=store.save(first.id)
        Thread.sleep(5)
        repeat(24) {add()}
        assertEquals(24,store.recent().size)
        assertFalse(store.recent().any {it.id==first.id})
        assertTrue(store.audio(saved.id,true).isFile)
        root.resolve("recent/00000000-0000-0000-0000-000000000000.pending").writeBytes(byteArrayOf(1))
        val active=store.begin("in progress")
        active.accept(byteArrayOf(1,2),0,2)
        val another=store.begin("another")
        assertFalse(root.resolve("recent/00000000-0000-0000-0000-000000000000.pending").exists())
        assertNotNull(active.finish(1,true,"完成"))
        another.finish(0,false,"")
    }
    @Test fun savingSentencePreservesExactPcmAndSampleRateWithoutResynthesis() {
        val root = Files.createTempDirectory("voice-keepsake").toFile()
        val store = VoiceKeepsakes(root.resolve("recent"), root.resolve("saved"))
        val capture = store.begin("选中的声音")
        capture.configure(22050)
        capture.segment("第一句。")
        capture.accept(byteArrayOf(1,2,3,4),0,4)
        capture.segment("第二句。")
        capture.accept(byteArrayOf(5,6,7,8,9,10),0,6)
        val track = capture.finish(5, true, "全文")!!
        val saved = store.save(track.id, 1)
        val bytes = store.audio(saved.id, true).readBytes()
        assertEquals("第二句。",saved.text)
        assertArrayEquals(byteArrayOf(5,6,7,8,9,10),bytes.copyOfRange(44,bytes.size))
        assertEquals(22050,ByteBuffer.wrap(bytes,24,4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(1,VoiceKeepsakes(root.resolve("recent"),root.resolve("saved")).saved().size)
    }
    @Test fun cancelledClipExcludesQueuedUnheardAudioAndIsLabelledPartial() {
        val root = Files.createTempDirectory("voice-keepsake").toFile()
        val store = VoiceKeepsakes(root.resolve("recent"),root.resolve("saved"))
        val capture = store.begin("声音")
        capture.segment("这是一句长话。")
        capture.accept(ByteArray(100){it.toByte()},0,100)
        val clip = capture.finish(12,false,"全文")!!
        assertFalse(clip.complete)
        assertEquals(68,store.audio(clip.id,false).length().toInt())
        val saved = store.save(clip.id,0)
        assertFalse(saved.complete)
        assertEquals(24,saved.frames.toInt()*2)
    }
    @Test fun noPlayedFramesCannotBecomeAFavoriteAndIdsCannotEscapeDirectory() {
        val root = Files.createTempDirectory("voice-keepsake").toFile()
        val store = VoiceKeepsakes(root.resolve("recent"),root.resolve("saved"))
        assertNull(store.begin("声音").finish(0,false,"未播放"))
        assertTrue(store.recent().isEmpty())
        try { store.audio("../secret",true); fail("path traversal") } catch (_: IllegalArgumentException) {}
    }
}
