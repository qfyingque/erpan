package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudSpeechTest {
    private fun config(url: String = "https://speech.invalid/v1") = SpeechConfig(url, "test-key", "stt", url, "test-key", "tts", "voice")
    private fun client(handler: (Request) -> ResponseBody) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(handler(chain.request())).build()
    }.build()

    @Test fun transcribeSendsWavAndReturnsTrimmedText() = runBlocking {
        val speech = CloudSpeech(config(), client { request ->
            assertEquals("https://speech.invalid/v1/audio/transcriptions", request.url.toString())
            assertEquals("Bearer test-key", request.header("Authorization"))
            val body = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
            val bytes = body.toString(Charsets.ISO_8859_1)
            assertTrue(bytes.contains("name=\"model\"\r\n\r\nstt"))
            assertTrue(bytes.contains("RIFF"))
            assertTrue(bytes.contains("WAVEfmt "))
            val start = bytes.indexOf("RIFF")
            assertEquals(16_000, java.nio.ByteBuffer.wrap(body, start + 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), body.copyOfRange(start + 44, start + 48))
            "{\"text\":\" 你好 \"}".toResponseBody("application/json".toMediaType())
        })
        assertEquals("你好", speech.transcribe(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun ttsRequestsPcmAndDeliversAlignedOrderedSamples() = runBlocking {
        val received = mutableListOf<Byte>()
        CloudSpeech(config(), client { request ->
            val json = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertTrue(json.contains("\"response_format\":\"pcm\""))
            byteArrayOf(1, 2, 3, 4, 5, 6).toResponseBody("application/octet-stream".toMediaType())
        }).speak("你好") { chunk -> assertEquals(0, chunk.size % 2); received.addAll(chunk.toList()) }
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), received)
    }

    @Test fun rejectsHtmlEmptyTranscriptionAndTruncatedPcm() = runBlocking {
        val html = CloudSpeech(config(), client { "<html>error</html>".toResponseBody("text/html".toMediaType()) })
        assertThrows(Exception::class.java) { runBlocking { html.speak("hi") { fail("HTML reached player") } } }
        val empty = CloudSpeech(config(), client { "{\"text\":\" \"}".toResponseBody("application/json".toMediaType()) })
        assertThrows(Exception::class.java) { runBlocking { empty.transcribe(byteArrayOf(1, 2)) } }
        val odd = CloudSpeech(config(), client { byteArrayOf(1, 2, 3).toResponseBody("audio/pcm".toMediaType()) })
        assertThrows(Exception::class.java) { runBlocking { odd.speak("hi") {} } }
        Unit
    }

    @Test fun cancellationCancelsActiveCallPromptly() = runBlocking {
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
            if (chain.call().isCanceled()) canceled.countDown()
            throw java.io.IOException("transport detail with test-key")
        }.build()
        val job = launch(Dispatchers.Default) { CloudSpeech(config(), http).transcribe(byteArrayOf(1, 2)) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(canceled.await(1, TimeUnit.SECONDS))
    }

    @Test fun invalidConfigAndErrorsNeverEchoSecrets() {
        for (url in listOf("http://host/v1", "https://user:secret@host/v1", "https://host/v1?key=secret")) {
            val error = assertThrows(IllegalArgumentException::class.java) { config(url).validate() }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
        assertFalse(config().toString().contains("test-key"))
    }

    @Test fun invalidHeaderKeyIsRejectedBeforeRequestConstructionWithoutEchoingIt() {
        val secret = "test-key\u0000private"
        val error = assertThrows(IllegalArgumentException::class.java) { config().copy(sttKey = secret).validate() }
        assertFalse(error.message.orEmpty().contains("test-key"))
    }

    @Test fun redirectIsRejectedWithoutFollowingOrEchoingItsBody() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(307).message("redirect")
                .header("Location", "https://other.invalid/stolen")
                .body("test-key and private text".toResponseBody()).build()
        }.build()
        val error = assertThrows(Exception::class.java) {
            runBlocking { CloudSpeech(config(), http).transcribe(byteArrayOf(1, 2)) }
        }
        assertEquals(1, calls)
        assertFalse(error.message.orEmpty().contains("test-key"))
    }
}
