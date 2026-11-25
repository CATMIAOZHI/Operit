package com.ai.assistance.operit.ui.common.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTranscriptsTest {
    private val voice = "![语音](file:///data/files/voice/a.wav)"

    @Test fun aBubbleWithOneVoiceIsForThatVoice() {
        assertEquals("file:///data/files/voice/a.wav", VoiceTranscripts.onlyVoiceIn("听听这个\n$voice"))
        // The same recording twice is still one.
        assertEquals("file:///data/files/voice/a.wav", VoiceTranscripts.onlyVoiceIn("$voice\n$voice"))
    }

    @Test fun picturesAndSeveralVoicesAreNotOne() {
        assertNull(VoiceTranscripts.onlyVoiceIn("![图](file:///p.png)"))
        assertNull(VoiceTranscripts.onlyVoiceIn("$voice\n![b](file:///data/files/voice/b.wav)"))
    }

    @Test fun anExampleInCodeIsNoVoice() {
        assertNull(VoiceTranscripts.onlyVoiceIn("这样写：\n```\n$voice\n```"))
        assertNull(VoiceTranscripts.onlyVoiceIn("这样写：`$voice`"))
        // A lone backtick inside a block does not pair with one after it.
        assertNull(VoiceTranscripts.onlyVoiceIn("~~~text\n`\n~~~\n示例：`$voice`"))
        // The real voice beside an example is still the one.
        assertEquals("file:///data/files/voice/c.wav",
            VoiceTranscripts.onlyVoiceIn("```\n$voice\n````\n![语音](file:///data/files/voice/c.wav)"))
    }
}