package com.ai.assistance.operit.api.voice

import com.ai.assistance.operit.api.speech.MimoSttProvider
import com.ai.assistance.operit.ui.features.settings.screens.SpeechKeyFamily
import com.ai.assistance.operit.ui.features.settings.screens.keyEndpointFor
import com.ai.assistance.operit.ui.features.settings.screens.voiceSampleFormat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MimoSpeechRequestTest {
    @Test fun presetSendsStyleAndVoiceAsPerSentencePlaceholders() {
        val mode = MimoVoiceProvider.Mode.PRESET
        assertEquals("自然地朗读。", MimoVoiceProvider.userMessage(mode, "", 1f, 1f))
        val body = JSONObject(MimoVoiceProvider.requestBody(mode, null))
        assertEquals("mimo-v2.5-tts", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals("{mimo_user}", messages.getJSONObject(0).getString("content"))
        assertEquals("{text}", messages.getJSONObject(1).getString("content"))
        assertEquals("{mimo_voice}", body.getJSONObject("audio").getString("voice"))
    }

    @Test fun styleAndSpeedGoInTheUserMessage() {
        val user = MimoVoiceProvider.userMessage(MimoVoiceProvider.Mode.CLONE, "温柔一点", 1.5f, 1f)
        assertEquals("温柔一点。语速是平常的 1.5 倍", user)
        val body = JSONObject(MimoVoiceProvider.requestBody(MimoVoiceProvider.Mode.CLONE, "data:audio/wav;base64,AAA="))
        assertEquals("mimo-v2.5-tts-voiceclone", body.getString("model"))
        assertEquals("data:audio/wav;base64,AAA=", body.getJSONObject("audio").getString("voice"))
    }

    @Test fun voiceDesignSendsTheDescriptionAndNoVoice() {
        val mode = MimoVoiceProvider.Mode.DESIGN
        assertEquals("年轻男声", MimoVoiceProvider.userMessage(mode, "年轻男声", 1f, 1f))
        assertEquals("", MimoVoiceProvider.userMessage(mode, "", 1f, 1f))
        val body = JSONObject(MimoVoiceProvider.requestBody(mode, null))
        assertEquals("mimo-v2.5-tts-voicedesign", body.getString("model"))
        assertFalse(body.getJSONObject("audio").has("voice"))
        val custom = JSONObject(MimoVoiceProvider.requestBody(MimoVoiceProvider.Mode.of("my-tts"), null, "my-tts"))
        assertEquals("my-tts", custom.getString("model"))
        assertEquals(MimoVoiceProvider.Mode.CLONE, MimoVoiceProvider.Mode.of("mimo-v3-tts-voiceclone"))
    }

    @Test fun textWithBracesIsNotFilledAgain() {
        val out = HttpVoiceProvider.replacePlaceholders("{text}|{rate}", mapOf("text" to "读 {rate} 原样", "rate" to "1.2")) { v, _, _ -> v }
        assertEquals("读 {rate} 原样|1.2", out)
    }

    @Test fun samplesAreKeptByFileNameOnly() {
        assertEquals("a.wav", MimoVoiceProvider.sampleName("/data/user/0/other.pkg/files/tts_voice_samples/a.wav"))
        assertEquals("b.mp3", MimoVoiceProvider.sampleName("b.mp3"))
        assertNull(MimoVoiceProvider.sampleName("冰糖"))
        assertNull(MimoVoiceProvider.sampleName("/etc/passwd"))
        assertFalse(MimoVoiceProvider.isSampleRef("data:audio/wav;base64,AAA="))
        assertEquals("mimo_default", MimoVoiceProvider.presetVoice("a1b2.wav"))
        assertEquals("冰糖", MimoVoiceProvider.presetVoice("冰糖"))
    }

    @Test fun onlyWavAndMp3RecordingsAreTaken() {
        assertEquals("wav", voiceSampleFormat("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("mp3", voiceSampleFormat("ID3\u0004".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("mp3", voiceSampleFormat(byteArrayOf(0xFF.toByte(), 0xFB.toByte())))
        assertNull(voiceSampleFormat(byteArrayOf(0xFF.toByte(), 0xF1.toByte())))
        assertNull(voiceSampleFormat("OggS".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test fun keysOnlyGoToTheServerTheyWereMadeFor() {
        assertEquals("", keyEndpointFor(SpeechKeyFamily.OPENAI, "https://api.openai.com/v1/chat/completions", ""))
        assertNull(keyEndpointFor(SpeechKeyFamily.OPENAI, "https://proxy.example.com/v1", ""))
        assertNull(keyEndpointFor(SpeechKeyFamily.OPENAI, "", "https://proxy.example.com/v1/audio/transcriptions"))
        assertEquals("", keyEndpointFor(SpeechKeyFamily.DEEPGRAM, "", ""))
        assertNull(keyEndpointFor(SpeechKeyFamily.OPENAI, "http://api.openai.com/v1", ""))
        assertEquals("", keyEndpointFor(SpeechKeyFamily.OPENAI, "https://api.openai.com/v1", "wss://api.openai.com/v1/realtime"))
        // A template field in the path is fine; one in the host makes the server unknown.
        assertEquals(com.ai.assistance.operit.data.preferences.SpeechOrigin(true, "tts.example.com", 443),
            com.ai.assistance.operit.data.preferences.SpeechOrigin.of("https://tts.example.com/say?q={text}"))
        assertNull(com.ai.assistance.operit.data.preferences.SpeechOrigin.of("https://{text}.example.com/say"))
        val plan = "https://token-plan-cn.xiaomimimo.com/v1"
        assertEquals("$plan/chat/completions", keyEndpointFor(SpeechKeyFamily.MIMO, plan, ""))
        assertNull(keyEndpointFor(SpeechKeyFamily.MIMO, "https://mimo-proxy.example.com/v1/chat/completions", ""))
        assertNull(keyEndpointFor(SpeechKeyFamily.MIMO, "https://evilxiaomimimo.com/v1", ""))
        assertNull(keyEndpointFor(SpeechKeyFamily.MIMO, plan, "https://proxy.example.com/v1/chat/completions"))
        assertTrue(keyEndpointFor(SpeechKeyFamily.MIMO, "", MimoVoiceProvider.DEFAULT_ENDPOINT_URL) == "")
    }

    @Test fun recognitionSendsWavAndReadsTheReply() {
        val file = java.io.File.createTempFile("sample", ".wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)); deleteOnExit() }
        val body = Base64FileBody("""{"v":"data:audio/wav;base64,""", file, """"}""", null)
        val sink = okio.Buffer()
        body.writeTo(sink)
        val sent = sink.readUtf8()
        assertEquals("""{"v":"data:audio/wav;base64,AQIDBAU="}""", sent)
        assertEquals(sent.length.toLong(), body.contentLength())
        // The streamed request is the same JSON as the plain one.
        val (head, tail) = MimoSttProvider.requestParts("m\"x", "zh")
        assertEquals(MimoSttProvider.requestJson("m\"x", "QQ==", "zh").toString().replace("\\/", "/"),
            JSONObject(head + "QQ==" + tail).toString().replace("\\/", "/"))
    }

    @Test fun recognitionRequestAndReply() {
        val body = MimoSttProvider.requestJson("mimo-v2.5-asr", "UklGRg==", "zh")
        val audio = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("input_audio", audio.getString("type"))
        assertEquals("data:audio/wav;base64,UklGRg==", audio.getJSONObject("input_audio").getString("data"))
        assertEquals("zh", body.getJSONObject("asr_options").getString("language"))
        assertEquals("auto", MimoSttProvider.requestJson("m", "x", "ja").getJSONObject("asr_options").getString("language"))
        assertEquals("你好", MimoSttProvider.transcript("""{"choices":[{"message":{"role":"assistant","content":" 你好 "}}]}"""))
    }
}
