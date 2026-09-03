package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.assistance.operit.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistorySelectorNewChatTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun newChatButton_emitsFolderInheritanceForTheSelectedCategory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recentLabel = context.getString(R.string.chat_category_recent)
        val allLabel = context.getString(R.string.chat_category_all)
        val newChatLabel = context.getString(R.string.new_chat)
        val observedInheritance = mutableListOf<Boolean>()

        composeRule.setContent {
            var selectedCategory by remember { mutableStateOf(ChatHistoryCategory.ALL) }
            MaterialTheme {
                ChatHistoryCategoryNewChatControls(
                    selectedCategory = selectedCategory,
                    canManageFolders = false,
                    onSelectedCategoryChange = { selectedCategory = it },
                    onNewChat = { inheritGroupFromCurrent ->
                        observedInheritance += inheritGroupFromCurrent
                    },
                    onCreateFolder = {},
                )
            }
        }

        composeRule.onNodeWithText(recentLabel).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(newChatLabel).performClick()

        composeRule.onNodeWithText(allLabel).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(newChatLabel).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(false, true), observedInheritance)
        }
    }
}
