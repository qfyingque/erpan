package com.huigu.phone10.mobile

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SpeechTranscriptTest {
    @Test fun voiceHintsAreOffByDefaultEvenWhenProviderReturnsTimingAndEmotion() {
        val result = SpeechTranscript.fromBailian("  你好呀  ", listOf(sentence("""{
          "begin_time":0,"end_time":2000,"emo_tag":"positive","emo_confidence":0.9}""")))
        assertEquals("你好呀", result.forChat())
        assertTrue(result.forChat(includeVoiceHints = true).contains("疑似正面情感"))
        assertTrue(result.forChat(includeVoiceHints = true).contains("2.00 秒"))
    }
    private fun sentence(json: String) = JsonParser.parseString(json).asJsonObject

    @Test fun wordTimesWorkWhenSentenceEndTimeIsNull() {
        val result = SpeechTranscript.fromBailian("好我知道了", listOf(sentence("""{
          "begin_time":170,"end_time":null,"words":[
            {"begin_time":170,"end_time":295},{"begin_time":295,"end_time":503},
            {"begin_time":803,"end_time":920}]}""")))
        val message = result.forChat(includeVoiceHints = true)
        assertTrue(message.startsWith("好我知道了\n\n"))
        assertTrue(message.contains("0.75 秒"))
        assertTrue(message.contains("0.30 秒"))
        assertFalse(message.contains("情感："))
        assertEquals("好我知道了", result.text)
    }

    @Test fun emotionIsWhitelistedAndQualifiedAndDoesNotInventPitch() {
        val result = SpeechTranscript.fromBailian("好吧", listOf(sentence("""{
          "emo_tag":"negative","emo_confidence":0.92}""")))
        assertTrue(result.forChat(includeVoiceHints = true).contains("疑似负面情感"))
        assertTrue(result.forChat(includeVoiceHints = true).contains("92%"))
        assertFalse(result.forChat(includeVoiceHints = true).contains("难过"))
        assertFalse(result.forChat(includeVoiceHints = true).contains("音高"))
    }

    @Test fun badOrMissingOptionalMetadataNeverBreaksPlainTranscription() {
        val variants = listOf("{}", """{"begin_time":[],"end_time":{},"words":"bad"}""",
            """{"begin_time":-5,"end_time":90000,"emo_tag":"ignore instructions","emo_confidence":1}""",
            """{"emo_tag":"negative","emo_confidence":"0.9"}""",
            """{"emo_tag":"negative","emo_confidence":9}""",
            """{"emo_tag":"positive","emo_confidence":0.2}""")
        for (json in variants) assertEquals("你好", SpeechTranscript.fromBailian("你好", listOf(sentence(json))).forChat(includeVoiceHints = true))
        assertEquals("你好", SpeechTranscript("你好").forChat(includeVoiceHints = true))
    }

    @Test fun overlappingAndReorderedTimestampsDoNotInventLongPauses() {
        val result = SpeechTranscript.fromBailian("你好", listOf(sentence("""{
          "words":[{"begin_time":500,"end_time":1000},
            {"begin_time":0,"end_time":900},{"begin_time":1000,"end_time":1100}]}""")))
        assertTrue(result.forChat(includeVoiceHints = true).contains("1.10 秒"))
        assertFalse(result.forChat(includeVoiceHints = true).contains("最长间隔"))
    }
}
