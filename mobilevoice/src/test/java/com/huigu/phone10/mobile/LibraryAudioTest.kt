package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class LibraryAudioTest {
    @get:Rule val folder=TemporaryFolder()
    @Test fun recentFileOpensAtPcmWithoutSavingAndRejectsCorruptHeader() {
        val store=VoiceKeepsakes(File(folder.root,"recent"),File(folder.root,"saved"))
        val cap=store.begin("voice")
        cap.accept(byteArrayOf(1,2,3,4),0,4)
        val clip=cap.finish(2,true,"words")!!
        store.openAudio(clip.id,false).use { input ->
            assertEquals(44L,input.filePointer)
            assertEquals(1,input.read())
            input.seek(46); assertEquals(3,input.read())
        }
        assertTrue(store.saved().isEmpty())
        java.io.RandomAccessFile(store.audio(clip.id,false),"rw").use { it.writeByte(0) }
        assertThrows(IllegalStateException::class.java) {store.openAudio(clip.id,false).close()}
    }

    @Test fun manualPauseSurvivesAudioFocusGainUntilTheUserResumes() = runBlocking {
        val writes=AtomicInteger()
        val sink=object:PcmSink {
            override fun write(data: ByteArray,offset:Int,length:Int):Int {writes.addAndGet(length);return length}
            override fun playedFrames()=writes.get()/2L
            override fun close()=Unit
        }
        val player=PcmPlayer(sink)
        player.setUserPaused(true)
        val writer=launch(Dispatchers.IO) {player.write(byteArrayOf(1,2,3,4))}
        try {
            delay(40);player.setPaused(false);delay(40)
            assertEquals(0,writes.get())
            player.setUserPaused(false)
            withTimeout(2000) {writer.join()}
            assertEquals(4,writes.get())
        } finally {player.close();writer.cancelAndJoin()}
    }

    @Test fun diagnosticMeasurementKeepsPcmByteIdenticalAcrossSplitWrites() {
        val out=ByteArrayOutputStream()
        val player=PcmPlayer(object:PcmSink {
            override fun write(data:ByteArray,offset:Int,length:Int):Int {out.write(data,offset,length);return length}
            override fun playedFrames()=out.size()/2L
            override fun underruns()=2
            override fun close()=Unit
        })
        val pcm=byteArrayOf(0,0,-1,127,0,-128,0,0)
        player.write(pcm.copyOfRange(0,3));player.write(pcm.copyOfRange(3,8));player.close()
        assertArrayEquals(pcm,out.toByteArray())
        val trace=VoiceDiagnostics.snapshot()
        assertTrue(trace.contains("pcm_peak_32768_clipped_2_jumps_3"))
        assertTrue(trace.contains("playback_underruns_2"))
        assertTrue(trace.contains("pcm_frames_sent_4_played_4"))
    }
}
