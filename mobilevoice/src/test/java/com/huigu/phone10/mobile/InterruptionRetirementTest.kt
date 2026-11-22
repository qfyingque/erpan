package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InterruptionRetirementTest {
    @Test fun cancellingAnIntermediateWaitCannotLoseTheOriginalCleanup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val flow = VoiceConversation(this, { it[0].toString() }, { text, _ ->
            sent += text
            if (text == "1") try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
        }, {}, {}, {})
        try {
            flow.submit(byteArrayOf(1)); started.await()
            flow.interrupt(); cleaning.await()
            val middle = flow.submit(byteArrayOf(2)); yield()
            flow.interrupt()
            val last = flow.submit(byteArrayOf(3)); yield(); delay(30)
            assertEquals("No new send until the oldest cancelled turn exits", listOf("1"), sent)
            release.complete(Unit)
            withTimeout(2000) { middle.join(); last.join() }
            assertEquals(listOf("1", "3"), sent)
        } finally { release.complete(Unit); flow.interrupt() }
    }
}
