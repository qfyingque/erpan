package com.huigu.phone10.mobile

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CloudEndJudgeTest {
    @Test fun boundedJudgeUsesOnlyItsOwnKeyAndDoesNotGuessMalformedAnswers() = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url("/v1/chat/completions")).build())
        }.build()
        try {
            val judge = CloudEndJudge(EndJudgeConfig(key = "JUDGE-ONLY"), client)
            for ((body, expected) in listOf("END" to true, "WAIT" to false, "Maybe" to null)) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"message":{"content":"$body"}}]}"""))
                assertEquals(expected, judge.isComplete("我想要"))
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("Bearer JUDGE-ONLY", request.getHeader("Authorization"))
                assertEquals("/v1/chat/completions", request.path)
                assertTrue(request.body.readUtf8().contains("我想要"))
            }
            server.enqueue(MockResponse().setResponseCode(401).setBody("private-error"))
            assertNull(judge.isComplete("你好"))
            server.takeRequest(2, TimeUnit.SECONDS)
            assertEquals(4, server.requestCount)
        } finally {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            server.shutdown(); client.dispatcher.executorService.shutdownNow()
        }
    }
    @Test fun judgeCredentialsAreNotShownAndUnsafeAddressesRejected() {
        assertFalse(EndJudgeConfig(key = "secret").toString().contains("secret"))
        listOf("http://example.com/v1", "https://user:secret@example.com/v1", "https://example.com/v1?key=secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { EndJudgeConfig(baseUrl = it, key = "key").validate() }
        }
    }
}
