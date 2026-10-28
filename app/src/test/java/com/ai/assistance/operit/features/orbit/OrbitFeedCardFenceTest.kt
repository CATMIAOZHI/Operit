package com.ai.assistance.operit.features.orbit

import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitFeedCardFenceTest {
    @Test
    fun fenceInBodyIsFoundAndRemoved() {
        val body = "所以怎么选：随手画用 App。\n\n```a2ui-ref\ncard-1\n```\n\n\n哪天你想直接画，说一声。"
        assertEquals(listOf("card-1"), OrbitBoardRepository.cardFences(body))
        assertEquals("所以怎么选：随手画用 App。\n\n哪天你想直接画，说一声。", OrbitBoardRepository.withoutCardFences(body))
    }

    @Test
    fun windowsLineEndsAndSpacesStillMatch() {
        val body = "前文\r\n\r\n``` a2ui-ref \r\n  card-2  \r\n```\r\n\r\n\r\n后文"
        assertEquals(listOf("card-2"), OrbitBoardRepository.cardFences(body))
        assertEquals("前文\n\n后文", OrbitBoardRepository.withoutCardFences(body))
    }

    @Test
    fun ordinaryTextIsUntouched() {
        val body = "提到 a2ui-ref 这个词，但没有代码块。\n\n```kotlin\nval x = 1\n```"
        assertEquals(emptyList<String>(), OrbitBoardRepository.cardFences(body))
        assertEquals(body, OrbitBoardRepository.withoutCardFences(body))
    }

    @Test
    fun exampleInsideAnotherCodeBlockIsKept() {
        val body = "写法示例：\n````\n```a2ui-ref\ncard-1\n```\n````\n结束"
        assertEquals(emptyList<String>(), OrbitBoardRepository.cardFences(body))
        assertEquals(body, OrbitBoardRepository.withoutCardFences(body))
        val tilde = "示例：\n~~~markdown\n```a2ui-ref\ncard-1\n```\n~~~\n结束"
        assertEquals(emptyList<String>(), OrbitBoardRepository.cardFences(tilde))
        assertEquals(tilde, OrbitBoardRepository.withoutCardFences(tilde))
    }

    @Test
    fun unclosedReferenceIsFastAndKept() {
        val body = "```a2ui-ref\n" + " ".repeat(5900) + "x"
        assertEquals(emptyList<String>(), OrbitBoardRepository.cardFences(body))
    }
}
