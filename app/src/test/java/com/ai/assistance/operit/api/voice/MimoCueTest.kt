package com.ai.assistance.operit.api.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimoCueTest {
    @Test fun tagsAreTakenOut() {
        assertEquals("再让我睡五分钟…… 就五分钟。", MimoVoiceProvider.withoutCues("(慵懒)再让我睡五分钟……（打哈欠） 就五分钟。"))
        assertEquals("Five more minutes… just five.", MimoVoiceProvider.withoutCues("(lazy)Five more minutes… (yawns) just five."))
        assertEquals("嗯。", MimoVoiceProvider.withoutCues("（长叹一口气）嗯。[deep breath]"))
        assertEquals("(慵懒 俏皮)", MimoVoiceProvider.leadingCue("(慵懒 俏皮)早"))
    }

    // Ordinary words in brackets are read and shown, wherever they are.
    @Test fun ordinaryBracketsStay() {
        listOf(
            "（周二）开会",
            "明天（看笑脸图标）",
            "明天（笑脸图标）换新",
            "下周（四川话课程）开课",
            "过敏成分（开心果）",
            "We meet tomorrow (Singapore time)",
            "Keep it (cold storage) please",
            "only (a little)",
        ).forEach { assertEquals(it, MimoVoiceProvider.withoutCues(it)) }
        assertFalse(MimoVoiceProvider.hasCues("（周二）开会"))
        assertEquals("", MimoVoiceProvider.leadingCue("（周二）开会"))
        assertTrue(MimoVoiceProvider.hasCues("好啊（轻笑）"))
    }
}
