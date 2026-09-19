package com.ai.assistance.operit.core.tools.packTool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ry builds append channel suffixes (`-ry.N`, `-dev`, `-dev.N`) to the upstream version name.
 * Parsing must tolerate unknown suffixes instead of throwing, because a parse failure would
 * disable ToolPkg loading and JS execution for every package.
 */
class ToolPkgOperitVersionSuffixTest {
    @Test
    fun `ry and dev suffixes keep the API 1_0_1 gate satisfied`() {
        listOf(
            "1.12.2-ry.1",
            "1.12.2-ry.1-dev",
            "1.12.2-ry.1-dev.5",
            "1.12.2+4",
            "1.12.1+4"
        ).forEach { operitVersion ->
            assertEquals(
                operitVersion,
                ToolPkgApiCompatibility.API_VERSION_1_0_1,
                ToolPkgApiCompatibility
                    .requireSupported(
                        apiVersion = ToolPkgApiCompatibility.API_VERSION_1_0_1,
                        operitVersion = operitVersion
                    )
                    .toString()
            )
        }
    }

    @Test
    fun `unknown channel suffixes degrade to no build number instead of failing`() {
        val supported =
            ToolPkgApiCompatibility.supportedApiVersions("1.12.2-nightly").map { it.toString() }

        assertEquals(
            listOf(
                ToolPkgApiCompatibility.LEGACY_API_VERSION,
                ToolPkgApiCompatibility.API_VERSION_1_0_1
            ),
            supported
        )
    }

    @Test
    fun `ry suffix without build stays below the API 1_0_1 gate`() {
        expectIllegalArgument("Supported ToolPkg API versions: 1.0.0") {
            ToolPkgApiCompatibility.requireSupported(
                apiVersion = ToolPkgApiCompatibility.API_VERSION_1_0_1,
                operitVersion = "1.12.1-ry.3"
            )
        }
    }

    @Test
    fun `unparseable operit version is rejected`() {
        expectIllegalArgument("Operit version is not supported") {
            ToolPkgApiCompatibility.supportedApiVersions("1.12.2abc")
        }
    }

    private fun expectIllegalArgument(
        messageFragment: String,
        block: () -> Unit
    ) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertTrue(
                "unexpected message: ${error.message}",
                error.message.orEmpty().contains(messageFragment)
            )
        }
    }
}
