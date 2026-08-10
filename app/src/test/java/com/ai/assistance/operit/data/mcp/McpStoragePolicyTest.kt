package com.ai.assistance.operit.data.mcp

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class McpStoragePolicyTest {

    @Test
    fun pluginPackagePathsStayBelowManagedRoot() {
        val root = Files.createTempDirectory("mcp-packages").toFile()
        try {
            val nested = requireNotNull(resolveMcpPackageDirectory(root, "owner/repository"))
            assertEquals(File(File(root, "owner"), "repository").canonicalFile, nested)

            listOf(
                "",
                ".",
                "..",
                "../outside",
                "owner/../../outside",
                "/absolute",
                "C:\\outside",
                "owner//repository",
                "owner\\..\\outside",
                "owner/.. ",
                "owner/repository.",
                "owner/repo:stream",
                "evil;rm",
                "evil value",
                "evil\$HOME",
                "evil`command`",
                "evil\$(command)",
            ).forEach { unsafe ->
                assertNull("must reject $unsafe", resolveMcpPackageDirectory(root, unsafe))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tempAndZipEntryPathsCannotEscapeTheirRoots() {
        val cache = Files.createTempDirectory("mcp-cache").toFile()
        val target = Files.createTempDirectory("mcp-extract").toFile()
        try {
            val temp = requireNotNull(safeMcpTempFile(cache, "owner/repository", "local"))
            assertEquals(cache.canonicalFile, temp.parentFile.canonicalFile)
            assertFalse(temp.name.contains("owner"))

            assertEquals(
                File(File(target, "nested"), "file.txt").canonicalFile,
                resolveMcpZipEntryTarget(target, "nested/file.txt"),
            )
            listOf(
                "docs/Getting Started.md",
                "文档/安装说明（本地）.md",
                "assets/icon (dark).png",
            ).forEach { ordinaryName ->
                assertEquals(
                    File(target, ordinaryName.replace('/', File.separatorChar)).canonicalFile,
                    resolveMcpZipEntryTarget(target, ordinaryName),
                )
            }
            assertNull(resolveMcpZipEntryTarget(target, "../outside.txt"))
            assertNull(resolveMcpZipEntryTarget(target, "/absolute.txt"))
            assertNull(resolveMcpZipEntryTarget(target, "C:/absolute.txt"))
            assertNull(resolveMcpZipEntryTarget(target, "C:\\absolute.txt"))
            assertNull(resolveMcpZipEntryTarget(target, "nested\\file.txt"))
            assertNull(resolveMcpZipEntryTarget(target, "nested\\..\\..\\outside.txt"))
            assertNull(resolveMcpZipEntryTarget(target, "nul\u0000name.txt"))
        } finally {
            cache.deleteRecursively()
            target.deleteRecursively()
        }
    }

    @Test
    fun legacyRuntimeRestoreSelectsOnlyEnabledInstalledLegacyServers() {
        val restored = mcpLegacyRuntimeRestoreIds(
            legacyOnlyIds = setOf("enabled", "disabled", "missing"),
            installedIds = setOf("enabled", "disabled"),
            isEnabled = { it != "disabled" },
        )

        assertEquals(listOf("enabled"), restored)
    }

    @Test
    fun legacyRuntimeSuppressionRejectsMissingAndSuppressedServers() {
        val legacyId = "legacy-only"
        try {
            assertFalse(
                McpRuntimeActivationGate.isActivationAllowed(
                    serverId = "missing",
                    hasEffectiveServer = false,
                    isEnabled = true,
                )
            )
            McpRuntimeActivationGate.suppressLegacy(listOf(legacyId))
            assertFalse(
                McpRuntimeActivationGate.isActivationAllowed(
                    serverId = legacyId,
                    hasEffectiveServer = true,
                    isEnabled = true,
                )
            )
        } finally {
            McpRuntimeActivationGate.resumeLegacyReads()
        }
        assertTrue(
            McpRuntimeActivationGate.isActivationAllowed(
                serverId = legacyId,
                hasEffectiveServer = true,
                isEnabled = true,
            )
        )
    }

    @Test
    fun runtimeGuardCleansUpWhenSuppressedDuringAProductionSideEffect() = runBlocking {
        var allowed = true
        var actionRan = false
        var cleanupRan = false

        val result = runWithMcpRuntimeActivationGuard(
            isAllowed = { allowed },
            onRevoked = { cleanupRan = true },
        ) {
            actionRan = true
            allowed = false
            "spawned"
        }

        assertTrue(actionRan)
        assertTrue(cleanupRan)
        assertNull(result)
    }

    @Test
    fun hiddenLegacyServerId_suppressesServerAndMetadata() {
        val serverId = "legacy-server"
        val legacy =
            MCPLocalServer.MCPConfig(
                mcpServers =
                    mutableMapOf(
                        serverId to MCPLocalServer.MCPConfig.ServerConfig(command = "npx")
                    ),
                pluginMetadata =
                    mutableMapOf(
                        serverId to
                            MCPLocalServer.PluginMetadata(
                                id = serverId,
                                name = "Legacy server",
                                description = ""
                            )
                    )
            )

        val merged =
            mergeMcpConfigs(
                internal = MCPLocalServer.MCPConfig(),
                legacy = legacy,
                hiddenLegacyServerIds = setOf(serverId)
            )

        assertFalse(merged.mcpServers.containsKey(serverId))
        assertFalse(merged.pluginMetadata.containsKey(serverId))
    }

    @Test
    fun hiddenLegacyId_doesNotSuppressAnExplicitInternalReplacement() {
        val serverId = "legacy-server"
        val internal =
            MCPLocalServer.MCPConfig(
                mcpServers =
                    mutableMapOf(
                        serverId to MCPLocalServer.MCPConfig.ServerConfig(command = "internal")
                    )
            )
        val legacy =
            MCPLocalServer.MCPConfig(
                mcpServers =
                    mutableMapOf(
                        serverId to MCPLocalServer.MCPConfig.ServerConfig(command = "legacy")
                    )
            )

        val merged =
            mergeMcpConfigs(
                internal = internal,
                legacy = legacy,
                hiddenLegacyServerIds = setOf(serverId)
            )

        assertTrue(merged.mcpServers[serverId]?.command == "internal")
    }

    @Test
    fun deleteWhileLegacySwitchIsOff_stillFindsPersistedEntryAndPreventsReappearance() {
        val serverId = "legacy-server"
        val persistedLegacy =
            MCPLocalServer.MCPConfig(
                mcpServers = mutableMapOf(
                    serverId to MCPLocalServer.MCPConfig.ServerConfig(command = "legacy")
                )
            )

        assertTrue(legacyMcpEntryExists(MCPLocalServer.MCPConfig(), persistedLegacy, serverId))
        val afterReEnable =
            mergeMcpConfigs(
                internal = MCPLocalServer.MCPConfig(),
                legacy = persistedLegacy,
                hiddenLegacyServerIds = setOf(serverId)
            )
        assertFalse(afterReEnable.mcpServers.containsKey(serverId))
    }

    @Test
    fun failedPersistence_doesNotPublishUpdatedConfig() = runBlocking {
        val original = MCPLocalServer.MCPConfig()
        val updated = MCPLocalServer.MCPConfig()
        val failure = IllegalStateException("disk full")
        var published = original

        val thrown = runCatching {
            published = persistMcpConfigBeforePublish(updated) { throw failure }
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertSame(original, published)
    }

    @Test
    fun failedLegacyOnlyMutation_doesNotPublishWriteOnCopyPromotion() = runBlocking {
        val serverId = "legacy-only"
        val original = MCPLocalServer.MCPConfig()
        val legacy = MCPLocalServer.MCPConfig(
            mcpServers = mutableMapOf(
                serverId to MCPLocalServer.MCPConfig.ServerConfig(command = "legacy")
            )
        )
        val promoted = promoteLegacyMcpEntry(original, legacy, emptySet(), serverId)
        var published = original

        runCatching {
            published = persistMcpConfigBeforePublish(promoted) {
                throw IllegalStateException("disk full")
            }
        }

        assertSame(original, published)
        assertFalse(published.mcpServers.containsKey(serverId))
    }

    @Test
    fun concurrentDeleteAndAdd_areSerializedWithoutLosingTheAdd() = runBlocking {
        val coordinator = McpConfigMutationCoordinator()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        var servers = mapOf("delete-me" to "old")

        val delete = async {
            coordinator.withLock {
                val snapshot = servers
                firstEntered.complete(Unit)
                releaseDelete.await()
                servers = snapshot - "delete-me"
            }
        }
        firstEntered.await()
        val add = async {
            coordinator.withLock {
                servers = servers + ("keep-me" to "new")
            }
        }
        releaseDelete.complete(Unit)
        delete.await()
        add.await()

        assertTrue("keep-me" in servers)
        assertFalse("delete-me" in servers)
    }

    @Test
    fun enableWaitsForSameIdUpdateAndPreservesLatestFields() = runBlocking {
        val coordinator = McpConfigMutationCoordinator()
        val updateEntered = CompletableDeferred<Unit>()
        val releaseUpdate = CompletableDeferred<Unit>()
        var config = MCPLocalServer.MCPConfig(
            mcpServers = mutableMapOf(
                "server" to MCPLocalServer.MCPConfig.ServerConfig(command = "old")
            )
        )

        val update = async {
            coordinator.withLock {
                updateEntered.complete(Unit)
                releaseUpdate.await()
                val servers = config.mcpServers.toMutableMap()
                servers["server"] = servers.getValue("server").copy(command = "new")
                config = config.copy(mcpServers = servers)
            }
        }
        updateEntered.await()
        val enable = async {
            coordinator.withLock {
                val servers = config.mcpServers.toMutableMap()
                servers["server"] = servers.getValue("server").copy(disabled = false)
                config = config.copy(mcpServers = servers)
            }
        }
        releaseUpdate.complete(Unit)
        update.await()
        enable.await()

        assertTrue(config.mcpServers.getValue("server").command == "new")
        assertFalse(config.mcpServers.getValue("server").disabled == true)
    }
}
