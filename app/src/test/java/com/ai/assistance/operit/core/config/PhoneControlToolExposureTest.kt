package com.ai.assistance.operit.core.config

import org.junit.Assert.*
import org.junit.Test

class PhoneControlToolExposureTest {
    @Test fun phoneControlIsNotExposedOutsideItsPackage() {
        listOf(
            SystemToolPrompts.getAIAllCategoriesCn(), SystemToolPrompts.getAIAllCategoriesEn(),
            SystemToolPrompts.getAllCategoriesCn(), SystemToolPrompts.getAllCategoriesEn()
        ).forEach { categories ->
            val tools = categories.flatMap { it.tools }
            assertFalse(tools.any { it.name.startsWith("phone_control") })
            assertFalse(tools.any { it.name == "run_ui_subagent" })
            assertFalse(tools.any { it.name == "tap" || it.name == "start_app" })
        }
    }
}
