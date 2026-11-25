package com.ai.assistance.operit.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitReservedNameTest {
    @Test
    fun refusesOperitInAnyCommonSpelling() {
        listOf("Operit", "Operit Ry", "operitry", "OPERIT", "Operit-Ry", "O.P.E.R.I.T", "O P E R I T", "Ｏｐｅｒｉｔ", "小 Operit")
            .forEach { assertTrue(it, OrbitSettings.isReservedName(it)) }
    }

    @Test
    fun keepsOrdinaryNames() {
        listOf("Orbit", "雨晴喵", "Rainy", "Proper Iteration", "Opera It", "Op Erit Ry")
            .forEach { assertFalse(it, OrbitSettings.isReservedName(it)) }
    }

    @Test
    fun reservedSavedNameReadsAsOrbit() {
        org.junit.Assert.assertEquals("Orbit", OrbitSettings.shownName("Operit Ry"))
        org.junit.Assert.assertEquals("雨晴喵", OrbitSettings.shownName("雨晴喵"))
    }

    @Test
    fun findsReservedNameInCommonIdentityLines() {
        val files = com.ai.assistance.operit.features.orbit.OrbitPromptFiles
        listOf(
            "Name: Operit Ry", "- name: Operit Ry", "- **Name**: Operit Ry", "- **Name:** Operit Ry",
            "* 名字：Operit", "名字: OPERIT", "# Me\n\n\n- **名字：** Operit Ry\n- 气质：安静",
        ).forEach { assertTrue(it, files.hasReservedName(it)) }
        listOf("- **Name**: 雨晴喵", "Nickname: Operit", "Talks about Operit sometimes")
            .forEach { assertFalse(it, files.hasReservedName(it)) }
    }
}
