package com.ai.assistance.operit.ui.features.github

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubLoginRemovalTest {
    private fun source(relativePath: String): String =
        File(relativePath).readText().replace("\r\n", "\n")

    @Test
    fun `personal distribution does not expose github login entry points`() {
        val settings =
            source(
                "src/main/java/com/ai/assistance/operit/ui/features/settings/screens/SettingsScreen.kt",
            )
        val market =
            source(
                "src/main/java/com/ai/assistance/operit/ui/features/packages/screens/UnifiedMarketScreen.kt",
            )
        val activity =
            source(
                "src/main/java/com/ai/assistance/operit/ui/main/MainActivity.kt",
            )
        val screens =
            source(
                "src/main/java/com/ai/assistance/operit/ui/main/screens/OperitScreens.kt",
            )
        val manifest = source("src/main/AndroidManifest.xml")

        assertFalse(settings.contains("GitHubLoginWebViewDialog"))
        assertFalse(settings.contains("navigateToGitHubAccount"))
        assertFalse(market.contains("MarketHomeTab.MINE"))
        assertFalse(market.contains("GitHubLoginWebViewDialog"))
        assertFalse(activity.contains("GitHubOAuthCoordinator"))
        assertFalse(activity.contains("processPendingGitHubAuth"))
        assertFalse(screens.contains("data object GitHubAccount"))
        assertFalse(manifest.contains("github-oauth-callback"))
    }

    @Test
    fun `market remains browseable but account mutations are unavailable`() {
        val market =
            source(
                "src/main/java/com/ai/assistance/operit/ui/features/packages/screens/UnifiedMarketScreen.kt",
            )
        val detail =
            source(
                "src/main/java/com/ai/assistance/operit/ui/features/packages/screens/UnifiedMarketDetailEntryScreen.kt",
            )
        val packageManager =
            source(
                "src/main/java/com/ai/assistance/operit/ui/features/packages/screens/PackageManagerScreen.kt",
            )
        val screens =
            source("src/main/java/com/ai/assistance/operit/ui/main/screens/OperitScreens.kt")

        assertTrue(market.contains("MarketHomeTab.ALL"))
        assertTrue(market.contains("MarketHomeTab.CATEGORIES"))
        assertTrue(packageManager.contains("onOpenMarket: () -> Unit"))
        assertTrue(packageManager.contains("onClick = onOpenMarket"))
        assertTrue(screens.contains("onOpenMarket = { navigateTo(Market(MarketHomeTab.ALL)) }"))
        assertFalse(detail.contains("onPublishNewVersion"))
        assertFalse(detail.contains("GitHubAuthPreferences"))
        assertTrue(detail.contains("enabled = false"))
        assertTrue(detail.contains("canPost = false"))
    }

    @Test
    fun `android build has no github oauth build config`() {
        val buildScript = source("build.gradle.kts")
        val authPreferences =
            source(
                "src/main/java/com/ai/assistance/operit/data/preferences/GitHubAuthPreferences.kt",
            )

        assertFalse(buildScript.contains("\"GITHUB_CLIENT_ID\""))
        assertFalse(buildScript.contains("\"GITHUB_OAUTH_BROKER_BASE_URL\""))
        assertTrue(authPreferences.contains("const val GITHUB_CLIENT_ID = \"\""))
        assertTrue(authPreferences.contains("if (GITHUB_CLIENT_ID.isBlank())"))
    }
}
