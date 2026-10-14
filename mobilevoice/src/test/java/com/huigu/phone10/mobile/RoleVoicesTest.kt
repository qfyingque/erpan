package com.huigu.phone10.mobile

import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class RoleVoicesTest {
    private fun settings(): MobileSettings {
        val a=SpeechConfig.bailianDefaults().copy(ttsKey="private",voice="a")
        return MobileSettings(a,chatId="group",voiceProfiles=listOf(VoiceProfile("a","A声",a,null),VoiceProfile("b","B声",a.copy(voice="b"),null)))
    }
    @Test fun bindingsAreChatScopedPersistedAndDoNotChangeDefaultOrRecognition() {
        val original=settings()
        val bound=original.bindRoleVoice("Sol","b")
        assertEquals("b",bound.voiceForRole("Sol").speech.voice)
        assertEquals(original.speech,bound.speech)
        assertEquals("a",bound.copy(chatId="other").voiceForRole("Sol").speech.voice)
        assertEquals("a",bound.voiceForRole("Astra").speech.voice)
        assertEquals("b",Gson().fromJson(Gson().toJson(bound),MobileSettings::class.java).voiceForRole("Sol").speech.voice)
        assertFalse(bound.bindRoleVoice("Sol",null).hasRoleVoices())
    }
    @Test fun deletedBoundVoiceFailsInsteadOfSilentlyChoosingAnotherVoice() {
        val bound=settings().bindRoleVoice("Sol","b").removeVoiceProfile("b")
        assertTrue(bound.hasRoleVoices())
        try { bound.voiceForRole("Sol"); fail("missing voice must be visible") } catch (_: IllegalArgumentException) {}
    }
    @Test fun olderSettingsWithoutBindingsStillUseTheSavedDefault() {
        val json=Gson().toJson(settings())
        val restored=Gson().fromJson(json,MobileSettings::class.java)
        assertFalse(restored.hasRoleVoices())
        assertEquals("a",restored.voiceForRole("Sol").speech.voice)
    }
    @Test fun stopDiscardsSecondSpeakerAndLateFirstSpeakerText() = runBlocking {
        val snapshots=Channel<ReplySnapshot>(8);val heard=mutableListOf<String>()
        val listener=ReplyListener({after->if(after==null)ReplySnapshot(10,emptyList(),false) else snapshots.receive()},
            {error("wrong player")},{},{},1,
            playRole={role,chunks->launch{heard+=role;for(c in chunks)heard+=c;awaitCancellation()}})
        val job=launch{listener.run()}
        snapshots.send(ReplySnapshot(11,listOf(ObservedReply(11,"一。",true,"Sol")),true))
        snapshots.send(ReplySnapshot(12,listOf(ObservedReply(12,"二。",true,"Astra")),true))
        withTimeout(2000){while(!heard.contains("一。"))delay(1)}
        delay(10);listener.interrupt()
        snapshots.send(ReplySnapshot(13,listOf(ObservedReply(13,"迟到。",true,"Sol")),false))
        delay(20);job.cancelAndJoin()
        assertEquals(listOf("Sol","一。"),heard)
    }
    @Test fun identityChangeWithinMessageStopsItWithoutReplaying() = runBlocking {
        val snapshots=Channel<ReplySnapshot>(8);val heard=mutableListOf<String>()
        val listener=ReplyListener({after->if(after==null)ReplySnapshot(10,emptyList(),false) else snapshots.receive()},
            {error("wrong player")},{},{},1,
            playRole={role,chunks->launch{heard+=role;for(c in chunks)heard+=c}})
        val job=launch{listener.run()}
        snapshots.send(ReplySnapshot(10,listOf(ObservedReply(11,"一。",false,"Sol")),true))
        withTimeout(2000){while(!heard.contains("一。"))delay(1)}
        snapshots.send(ReplySnapshot(11,listOf(ObservedReply(11,"一。二。",true,"Astra")),false))
        delay(20);job.cancelAndJoin()
        assertEquals(listOf("Sol","一。"),heard)
    }
    @Test fun twoSpeakersKeepTheirIdentityAndWaitForPreviousPlayback() = runBlocking {
        val snapshots=Channel<ReplySnapshot>(8)
        val release=CompletableDeferred<Unit>();val heard=mutableListOf<String>()
        val listener=ReplyListener({after->if(after==null)ReplySnapshot(10, emptyList(),false) else snapshots.receive()},
            {error("legacy player used")},{},{},1,
            playRole={role,chunks->launch{heard+=role;for(c in chunks)heard+=c;if(role=="Sol")release.await()}})
        val job=launch{listener.run()}
        snapshots.send(ReplySnapshot(11,listOf(ObservedReply(11,"一句。",true,"Sol")),true))
        snapshots.send(ReplySnapshot(12,listOf(ObservedReply(12,"二句。",true,"Astra")),false))
        withTimeout(2000){while(!heard.contains("一句。"))delay(1)}
        delay(20);assertFalse(heard.contains("Astra"));release.complete(Unit)
        withTimeout(2000){while(!heard.contains("二句。"))delay(1)}
        job.cancelAndJoin();assertEquals(listOf("Sol","一句。","Astra","二句。"),heard)
    }
}
