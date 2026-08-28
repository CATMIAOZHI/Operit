package com.ai.assistance.operit.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialModelCapabilitiesCatalogTest {
    private val catalog =
        OfficialModelCapabilitiesCatalog.parse(
            """
            {
              "deepseek/deepseek-v4-flash": {
                "name": "DeepSeek V4 Flash",
                "family": "deepseek-flash",
                "modalities": {"input": ["text"], "output": ["text"]}
              },
              "deepseek/deepseek-v4-flash-vision-exp": {
                "name": "DeepSeek V4 Flash Vision Exp",
                "family": "deepseek-flash",
                "modalities": {"input": ["text", "image"], "output": ["text"]}
              },
              "moonshotai/kimi-k2.6": {
                "name": "Kimi K2.6",
                "family": "kimi-k2",
                "modalities": {"input": ["text", "image", "video"], "output": ["text"]}
              },
              "zhipuai/glm-5.3-flash": {
                "name": "GLM-5.3-Flash",
                "family": "glm",
                "modalities": {
                  "input": ["text", "image", "audio", "video", "pdf"],
                  "output": ["text"]
                }
              }
            }
            """.trimIndent(),
        )

    @Test
    fun `matches canonical model id without provider configuration`() {
        val result = catalog.lookup("moonshotai/kimi-k2.6")
        assertTrue(result is OfficialModelCapabilitiesLookup.Match)
        val match = result as OfficialModelCapabilitiesLookup.Match

        assertEquals("moonshotai/kimi-k2.6", match.model.officialModelId)
        assertEquals(
            ModelMultimodalCapabilities(image = true, video = true),
            match.model.capabilities,
        )
    }

    @Test
    fun `matches a unique short id behind an arbitrary gateway`() {
        val result = catalog.lookup("opencode-go/kimi-k2.6")
        assertTrue(result is OfficialModelCapabilitiesLookup.Match)
        val match = result as OfficialModelCapabilitiesLookup.Match

        assertEquals("moonshotai/kimi-k2.6", match.model.officialModelId)
    }

    @Test
    fun `matches a route id that prefixes the official short id`() {
        val result = catalog.lookup("command-code/deepseek-deepseek-v4-flash")
        assertTrue(result is OfficialModelCapabilitiesLookup.Match)
        val match = result as OfficialModelCapabilitiesLookup.Match

        assertEquals("deepseek/deepseek-v4-flash", match.model.officialModelId)
        assertFalse(match.model.capabilities.image)
    }

    @Test
    fun `maps only the media types supported by Operit`() {
        val result = catalog.lookup("glm-5.3-flash")
        assertTrue(result is OfficialModelCapabilitiesLookup.Match)
        val match = result as OfficialModelCapabilitiesLookup.Match

        assertTrue(match.model.capabilities.image)
        assertTrue(match.model.capabilities.audio)
        assertTrue(match.model.capabilities.video)
    }

    @Test
    fun `returns candidates instead of guessing a partial model id`() {
        val result = catalog.lookup("deepseek-v4")
        assertTrue(result is OfficialModelCapabilitiesLookup.Candidates)
        val candidates = result as OfficialModelCapabilitiesLookup.Candidates

        assertEquals(
            listOf(
                "deepseek/deepseek-v4-flash",
                "deepseek/deepseek-v4-flash-vision-exp",
            ),
            candidates.models.map { it.officialModelId },
        )
    }

    @Test
    fun `one sync resolves every uniquely matched model in the configuration`() {
        val matches =
            catalog.matchAll(
                listOf(
                    "opencode-go/kimi-k2.6",
                    "command-code/deepseek-deepseek-v4-flash",
                    "my-private-model",
                )
            )

        assertEquals(
            setOf(
                "opencode-go/kimi-k2.6",
                "command-code/deepseek-deepseek-v4-flash",
            ),
            matches.keys,
        )
        assertTrue(matches.getValue("opencode-go/kimi-k2.6").video)
        assertFalse(
            matches.getValue("command-code/deepseek-deepseek-v4-flash").image
        )
    }

    @Test
    fun `returns not found for an unrelated custom model`() {
        assertTrue(
            catalog.lookup("my-private-model") is OfficialModelCapabilitiesLookup.NotFound,
        )
    }

    @Test
    fun `does not treat a private model with an official short id suffix as official`() {
        assertTrue(
            catalog.lookup("vendor/my-private-kimi-k2.6") is
                OfficialModelCapabilitiesLookup.Candidates,
        )
    }

    @Test
    fun `normalized official id collision remains ambiguous`() {
        val collidingCatalog =
            OfficialModelCapabilitiesCatalog.parse(
                """
                {
                  "vendor/foo_bar": {
                    "name": "Foo underscore",
                    "modalities": {"input": ["text", "image"]}
                  },
                  "vendor/foo-bar": {
                    "name": "Foo hyphen",
                    "modalities": {"input": ["text"]}
                  }
                }
                """.trimIndent()
            )

        assertTrue(
            collidingCatalog.lookup("vendor/foo-bar") is
                OfficialModelCapabilitiesLookup.Candidates,
        )
        assertTrue(collidingCatalog.matchAll(listOf("vendor/foo-bar")).isEmpty())
    }
}
