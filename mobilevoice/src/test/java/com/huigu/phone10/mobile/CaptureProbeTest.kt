package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

class CaptureProbeTest {
    @Test fun waveformStatsDistinguishZeroGapsFromSignedFullScale() {
        val pcm=ByteBuffer.allocate(32000).order(ByteOrder.LITTLE_ENDIAN)
        pcm.putShort(Short.MIN_VALUE); pcm.putShort(Short.MAX_VALUE)
        val stats=CaptureProbe.metrics(pcm.array())
        assertEquals(1.0,stats.seconds,0.0)
        assertEquals(1.0,stats.peak,0.0)
        assertEquals(15998.0/16000,stats.zeroFraction,0.0)
        assertEquals(999.875,stats.longestZeroMillis,0.0)
    }
    @Test fun exportPreservesEverySampleAndContainsOnlyExplicitlySelectedSamples() {
        val pcm=byteArrayOf(0,0,1,0,-1,-1)
        val sample=ProbeSample(pcm,"source=9",CaptureProbe.metrics(pcm),"unverified")
        val bytes=ByteArrayOutputStream()
        CaptureProbe.export(bytes,mapOf(ProbeMode.MINIMAL to sample))
        val entries=mutableMapOf<String,ByteArray>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while(true) { val e=zip.nextEntry?:break; entries[e.name]=zip.readBytes() }
        }
        assertEquals(setOf("minimal.wav","report.json"),entries.keys)
        val wav=entries.getValue("minimal.wav")
        assertArrayEquals(pcm,wav.copyOfRange(44,wav.size))
        assertEquals(16000,ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
    }
    @Test fun resultStorageIsBoundedAndClearingDoesNotStopAnActiveProbe() {
        CaptureProbe.clear()
        val sample=ProbeSample(ByteArray(2),"",CaptureProbe.metrics(ByteArray(2)),"")
        repeat(10) { CaptureProbe.result(ProbeMode.CURRENT,sample) }
        assertEquals(1,CaptureProbe.state.value.samples.size)
        CaptureProbe.busy("recording"); CaptureProbe.clear()
        assertTrue(CaptureProbe.state.value.busy)
        CaptureProbe.finish("done"); CaptureProbe.clear()
        assertTrue(CaptureProbe.state.value.samples.isEmpty())
    }
}
