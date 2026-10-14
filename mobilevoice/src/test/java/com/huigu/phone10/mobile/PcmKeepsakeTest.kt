package com.huigu.phone10.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PcmKeepsakeTest {
    @Test fun acceptedAudioExcludesPrimingSilenceAndSurvivesTrackClose() = runBlocking {
        val root = Files.createTempDirectory("pcm-capture").toFile()
        val store = VoiceKeepsakes(root.resolve("recent"),root.resolve("saved"))
        val capture = store.begin("test")
        var written = 0L
        val sink = object : PcmSink {
            override fun write(data: ByteArray, offset: Int, length: Int): Int = minOf(3,length).also { written += it }
            override fun playedFrames() = written/2
            override fun endPaddingBytes() = 20
            override fun close() { written=0 }
        }
        val player = PcmPlayer(sink).also { it.captureObserver=capture }
        player.markCaptionSegment("一句话")
        player.write(byteArrayOf(1,2,3));player.write(byteArrayOf(4,5,6))
        player.drain();player.close()
        val clip = capture.finish(player.archiveFrames(),true,"")!!
        assertEquals(3L,clip.frames)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6),store.audio(clip.id,false).readBytes().drop(44).toByteArray())
    }

    @Test fun cancelledPlaybackKeepsOnlyHeadAndBrokenArchiveCannotStopSound() {
        var accepted = 0
        val sink = object : PcmSink {
            override fun write(data: ByteArray, offset: Int, length: Int) = length.also { accepted+=it }
            override fun playedFrames()=4L
            override fun close() = Unit
        }
        val player=PcmPlayer(sink)
        player.captureObserver=object : PcmCaptureObserver {
            override fun configure(sampleRate:Int)=Unit
            override fun segment(text:String)=Unit
            override fun accept(data:ByteArray,offset:Int,count:Int) { error("disk full") }
        }
        player.write(ByteArray(100));player.close()
        assertEquals(100,accepted)
        assertEquals(4L,player.archiveFrames())
    }
}
