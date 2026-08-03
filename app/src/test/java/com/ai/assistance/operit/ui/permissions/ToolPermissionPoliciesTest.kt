package com.ai.assistance.operit.ui.permissions

import com.ai.assistance.operit.core.tools.PermissionReviewSubmissionTool
import com.ai.assistance.operit.core.tools.PermissionReviewSubmissionRegistry
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.api.chat.enhance.shouldInterruptPendingToolBatch
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.FunctionConfigMapping
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.StreamLogger
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import kotlinx.coroutines.runBlocking

class ToolPermissionPoliciesTest {
    @Test
    fun permissionLevelParsingPreservesLegacyAndFailsUnknownToAsk() {
        assertEquals(PermissionLevel.ALLOW, PermissionLevel.fromString("ALLOW"))
        assertEquals(PermissionLevel.WORKSPACE, PermissionLevel.fromString("WORKSPACE"))
        assertEquals(
            PermissionLevel.WORKSPACE_REVIEWER,
            PermissionLevel.fromString("WORKSPACE_REVIEWER"),
        )
        assertEquals(PermissionLevel.REVIEWER, PermissionLevel.fromString("REVIEWER"))
        assertEquals(PermissionLevel.ASK, PermissionLevel.fromString("CAUTION"))
        assertEquals(PermissionLevel.ASK, PermissionLevel.fromString("unexpected"))
        assertEquals(PermissionLevel.ASK, PermissionLevel.fromString(null))
    }

    @Test
    fun combinedWorkspaceReviewerRoutesInsideToAllowAndEverythingElseToReviewer() {
        assertEquals(
            PermissionRoute.ALLOW,
            resolvePermissionRoute(PermissionLevel.WORKSPACE_REVIEWER, workspaceApproved = true),
        )
        assertEquals(
            PermissionRoute.REVIEWER,
            resolvePermissionRoute(PermissionLevel.WORKSPACE_REVIEWER, workspaceApproved = false),
        )
        assertEquals(
            PermissionRoute.ASK,
            resolvePermissionRoute(PermissionLevel.WORKSPACE, workspaceApproved = false),
        )
    }

    @Test
    fun permanentPerToolAllowOrDenyOverridesCombinedGlobalMode() {
        assertEquals(
            PermissionLevel.ALLOW,
            resolveEffectivePermissionLevel(
                masterLevel = PermissionLevel.WORKSPACE_REVIEWER,
                toolOverride = PermissionLevel.ALLOW,
            ),
        )
        assertEquals(
            PermissionLevel.FORBID,
            resolveEffectivePermissionLevel(
                masterLevel = PermissionLevel.WORKSPACE_REVIEWER,
                toolOverride = PermissionLevel.FORBID,
            ),
        )
        assertEquals(
            PermissionLevel.WORKSPACE_REVIEWER,
            resolveEffectivePermissionLevel(
                masterLevel = PermissionLevel.WORKSPACE_REVIEWER,
                toolOverride = null,
            ),
        )
    }

    @Test
    fun permanentOverrideWinsIfItChangesWhileReviewerOrExplicitApprovalIsPending() {
        assertTrue(
            resolveApprovalDecisionWithPermanentOverride(
                approvalGranted = false,
                latestEffectiveLevel = PermissionLevel.ALLOW,
            )
        )
        assertFalse(
            resolveApprovalDecisionWithPermanentOverride(
                approvalGranted = true,
                latestEffectiveLevel = PermissionLevel.FORBID,
            )
        )
        assertTrue(
            resolveApprovalDecisionWithPermanentOverride(
                approvalGranted = true,
                latestEffectiveLevel = PermissionLevel.WORKSPACE_REVIEWER,
            )
        )
        assertFalse(
            resolveApprovalDecisionWithPermanentOverride(
                approvalGranted = false,
                latestEffectiveLevel = PermissionLevel.ASK,
            )
        )
        assertTrue(
            resolveApprovalDecisionWithPermanentOverride(
                approvalGranted = true,
                latestEffectiveLevel = PermissionLevel.ASK,
            )
        )
    }

    @Test
    fun refreshedMasterRouteWinsOverAnInFlightReviewerDecision() {
        assertEquals(
            permissionDeniedBySettings(),
            resolveReviewDecisionAfterSettingsRefresh(
                approvalGranted = true,
                latestRoute = PermissionRoute.FORBID,
                reviewerRationale = "Reviewer allowed",
            ),
        )
        assertEquals(
            ToolPermissionDecision.Allowed,
            resolveReviewDecisionAfterSettingsRefresh(
                approvalGranted = false,
                latestRoute = PermissionRoute.ALLOW,
                reviewerRationale = "Reviewer denied",
            ),
        )
        assertNull(
            resolveReviewDecisionAfterSettingsRefresh(
                approvalGranted = true,
                latestRoute = PermissionRoute.ASK,
                reviewerRationale = "Reviewer allowed",
            )
        )
        val reviewDenial =
            resolveReviewDecisionAfterSettingsRefresh(
                approvalGranted = false,
                latestRoute = PermissionRoute.REVIEWER,
                reviewerRationale = "destructive action",
            ) as ToolPermissionDecision.Denied
        assertEquals(ToolPermissionDenialSource.AUTOMATIC_REVIEW, reviewDenial.source)
        assertTrue(reviewDenial.rejection.contains("Do not retry"))
        assertEquals("Tool execution denied by user.", permissionDeniedByUser().rejection)
    }

    @Test
    fun reviewEventStatusReflectsTheDecisionActuallyEnforced() {
        assertEquals(
            PermissionReviewStatus.APPROVED,
            reviewEventStatusForEnforcedDecision(ToolPermissionDecision.Allowed),
        )
        assertEquals(
            PermissionReviewStatus.DENIED,
            reviewEventStatusForEnforcedDecision(permissionDeniedByUser()),
        )
        assertEquals(
            PermissionReviewStatus.DENIED,
            reviewEventStatusForEnforcedDecision(permissionDeniedBySettings()),
        )
        assertEquals(
            PermissionReviewStatus.DENIED,
            reviewEventStatusForEnforcedDecision(
                permissionDeniedByAutomaticReview("not authorized")
            ),
        )
    }

    @Test
    fun workspacePolicyRequiresBoundWorkspace() {
        assertTrue(WorkspaceToolPermissionPolicy.hasActiveWorkspace("/workspace/project"))
        assertFalse(WorkspaceToolPermissionPolicy.hasActiveWorkspace("  "))
        assertFalse(WorkspaceToolPermissionPolicy.hasActiveWorkspace(null))
    }

    @Test
    fun workspacePolicyAllowsOnlyFileTargetsInsideMatchingWorkspaceEnvironment() {
        val workspace = Files.createTempDirectory("operit-workspace-policy").toFile().canonicalFile
        val inside = File(workspace, "src/main.kt")
        val sibling = File(workspace.parentFile, "${workspace.name}-outside/main.kt")

        assertTrue(workspaceAllows(tool("read_file", "path" to inside.path), workspace.path))
        assertFalse(workspaceAllows(tool("read_file", "path" to sibling.path), workspace.path))
        assertFalse(
            workspaceAllows(
                tool("read_file", "path" to inside.path, "environment" to "linux"),
                workspace.path,
            )
        )
        assertFalse(
            workspaceAllows(
                tool("read_file", "path" to "/etc/hosts", "environment" to "linux "),
                workspacePath = "/etc",
                workspaceEnv = "linux",
            )
        )
        assertFalse(
            workspaceAllows(tool("read_file", "path" to " ${inside.path}"), workspace.path)
        )
        assertFalse(workspaceAllows(tool("read_file", "path" to "src/main.kt"), workspace.path))
    }

    @Test
    fun workspacePolicyAllowsSafeConfinedSinglePathFileTools() {
        val workspace = Files.createTempDirectory("operit-workspace-policy").toFile().canonicalFile
        val insideSource = File(workspace, "source.txt")
        val insideDestination = File(workspace, "nested/destination.txt")
        val outside = File(workspace.parentFile, "outside.txt")

        listOf(
                "read_file",
                "read_file_part",
                "read_file_full",
                "read_file_binary",
                "list_files",
                "grep_code",
                "grep_context",
                "write_file",
                "write_file_binary",
                "file_exists",
                "make_directory",
                "file_info",
                "create_file",
                "edit_file",
                "apply_file",
            )
            .forEach { toolName ->
                assertTrue(
                    toolName,
                    workspaceAllows(tool(toolName, "path" to insideSource.path), workspace.path),
                )
            }

        listOf("list_files", "grep_code", "grep_context", "edit_file", "apply_file")
            .forEach { toolName ->
                assertFalse(
                    toolName,
                    workspaceAllows(tool(toolName, "path" to outside.path), workspace.path),
                )
                assertFalse(
                    toolName,
                    workspaceAllows(
                        tool(toolName, "path" to insideSource.path, "environment" to "linux"),
                        workspace.path,
                    ),
                )
                assertFalse(toolName, workspaceAllows(tool(toolName), workspace.path))
            }

        assertFalse(
            workspaceAllows(
                tool("delete_file", "path" to workspace.path, "recursive" to "true"),
                workspace.path,
            )
        )

        listOf("move_file", "copy_file", "zip_files", "unzip_files").forEach { toolName ->
            assertFalse(
                toolName,
                workspaceAllows(
                    tool(
                        toolName,
                        "source" to insideSource.path,
                        "destination" to insideDestination.path,
                    ),
                    workspace.path,
                ),
            )
        }

        assertFalse(workspaceAllows(tool("find_files", "path" to workspace.path), workspace.path))
        assertFalse(
            workspaceAllows(
                tool(
                    "copy_file",
                    "source" to insideSource.path,
                    "destination" to insideDestination.path,
                    "source_environment" to "android",
                    "dest_environment" to "linux",
                ),
                workspace.path,
            )
        )
    }

    @Test
    fun workspacePolicyDoesNotAutoApproveToolsWithExternalSideEffects() {
        val workspace = Files.createTempDirectory("operit-workspace-policy").toFile().canonicalFile
        val inside = File(workspace, "artifact.apk")

        listOf("open_file", "share_file", "install_app").forEach { toolName ->
            assertFalse(toolName, workspaceAllows(tool(toolName, "path" to inside.path), workspace.path))
        }
        assertFalse(
            workspaceAllows(
                tool("download_file", "destination" to inside.path, "url" to "https://example.com"),
                workspace.path,
            )
        )
        assertFalse(
            workspaceAllows(
                tool("multipart_request", "files" to "[\"${inside.path}\"]"),
                workspace.path,
            )
        )
    }

    @Test
    fun workspacePolicyRejectsCanonicalSymlinkEscapeWithoutCreatingRealSymlink() {
        val workspace = File("C:/workspace").canonicalPath
        val escapedTarget = File("C:/outside/secret.txt").canonicalPath

        assertFalse(
            WorkspaceToolPermissionPolicy.isAutoApproved(
                tool = tool("read_file", "path" to "C:/workspace/link/secret.txt"),
                workspacePath = "C:/workspace",
                workspaceEnv = "android",
                canonicalPathResolver = { path, _ ->
                    if (path == "C:/workspace/link/secret.txt") escapedTarget else workspace
                },
            )
        )
    }

    @Test
    fun workspacePolicyNormalizesRepositoryPathsAndRejectsEscapes() {
        assertTrue(
            workspaceAllows(
                tool("write_file", "path" to "/src/main.ts", "environment" to "repo:demo"),
                workspacePath = "/",
                workspaceEnv = "repo:demo",
            )
        )
        assertFalse(
            workspaceAllows(
                tool("write_file", "path" to "/../outside", "environment" to "repo:demo"),
                workspacePath = "/project",
                workspaceEnv = "repo:demo",
            )
        )
        assertFalse(
            workspaceAllows(
                tool("write_file", "path" to "/src/main.ts", "environment" to "repo:other"),
                workspacePath = "/",
                workspaceEnv = "repo:demo",
            )
        )
        assertFalse(
            workspaceAllows(
                tool("write_file", "path" to "/src/main.ts", "environment" to "repo:demo"),
                workspacePath = "/",
                workspaceEnv = "repo:Demo",
            )
        )
        assertFalse(
            workspaceAllows(
                tool("write_file", "path" to "/src/main.ts", "environment" to "REPO:demo"),
                workspacePath = "/",
                workspaceEnv = "repo:demo",
            )
        )
    }

    @Test
    fun workspacePolicyAllowsOnlyTrustedStatelessShellCommandsWithScopedPaths() {
        val workspace = "/workspace/project"
        val outside = "/workspace/outside.txt"

        assertTrue(
            workspaceAllows(
                tool(
                    "execute_shell",
                    "command" to "/system/bin/cat '/workspace/project/src/main.kt'",
                ),
                workspace,
            )
        )
        assertFalse(
            workspaceAllows(
                tool("execute_in_terminal_session", "command" to "/bin/rm build/output.txt"),
                workspace,
                workspaceEnv = "linux",
                terminalCurrentDirectory = workspace,
            )
        )
        assertFalse(
            workspaceAllows(
                tool("execute_shell", "command" to "/system/bin/cat '$outside'"),
                workspace,
            )
        )
    }

    @Test
    fun workspacePolicyPromptsForUnknownDynamicOrUnanchoredShellScope() {
        val workspace = "/workspace/project"

        assertFalse(
            workspaceAllows(
                tool("execute_shell", "command" to "cat \"${'$'}HOME/secret\""),
                workspace,
            )
        )
        assertFalse(
            workspaceAllows(
                tool("execute_shell", "command" to "python -c 'open(\"/tmp/out\", \"w\")'"),
                workspace,
            )
        )
        assertFalse(
            workspaceAllows(
                tool("execute_shell", "command" to "/system/bin/cat relative.txt"),
                workspace,
            )
        )
        assertFalse(
            workspaceAllows(
                tool(
                    "execute_shell",
                    "command" to "cd '$workspace' || cat relative.txt",
                ),
                workspace,
            )
        )
        assertFalse(
            workspaceAllows(
                tool(
                    "execute_shell",
                    "command" to "cd '$workspace' && sed -e 'w /tmp/out' file.txt",
                ),
                workspace,
            )
        )
        assertFalse(
            workspaceAllows(
                tool(
                    "execute_shell",
                    "command" to
                        "/system/bin/cat '/workspace/project/file.txt' | /system/bin/cat '/workspace/project/file.txt'",
                ),
                workspace,
            )
        )
        assertFalse(
            workspaceAllows(
                tool(
                    "execute_shell",
                    "command" to "cd '$workspace' && ./echo",
                ),
                workspace,
            )
        )
        assertFalse(
            workspaceAllows(
                tool(
                    "execute_shell",
                    "command" to
                        "/system/bin/cat '/workspace/project/file.txt' `rm /outside/file`",
                ),
                workspace,
            )
        )
        assertFalse(
            workspaceAllows(
                tool(
                    "execute_shell",
                    "command" to
                        "/system/bin/cat /workspace/project/safe\\ /etc/passwd",
                ),
                workspace,
            )
        )
        assertFalse(workspaceAllows(tool("http_request", "url" to "https://example.com"), workspace))
        assertFalse(workspaceAllows(tool("share_file", "path" to "/workspace/project/secret.txt"), workspace))
    }

    @Test
    fun bundledSuperAdminWrappersRequireTheSameProvableCommandScope() {
        val workspace = Files.createTempDirectory("operit-workspace-wrapper").toFile().canonicalFile
        assertFalse(
            workspaceAllows(
                tool("create_terminal_session", "session_name" to "workspace-session"),
                workspace.path,
                workspaceEnv = "linux",
            )
        )
        assertFalse(
            workspaceAllows(
                tool(
                    "super_admin:terminal",
                    "command" to "cd '${workspace.path}' && cat README.md",
                ),
                workspace.path,
                workspaceEnv = "linux",
            )
        )
        assertFalse(
            workspaceAllows(
                tool(
                    "super_admin:shell",
                    "command" to "/system/bin/cat '${File(workspace, "README.md").path}'",
                ),
                workspace.path,
            )
        )
        assertFalse(
            workspaceAllows(
                tool("super_admin:shell", "command" to "pm list packages"),
                workspace.path,
            )
        )
    }

    @Test
    fun reviewerHostPolicyEnforcesRiskAndAuthorization() {
        val low =
            PermissionReviewResponsePolicy.parseAndEnforce(
                reviewTool("allow", "low", "unknown", "Routine read")
            )
        assertNotNull(low)
        assertEquals(PermissionReviewOutcome.ALLOW, low?.outcome)

        val unauthorizedHigh =
            PermissionReviewResponsePolicy.parseAndEnforce(
                reviewTool("allow", "high", "low", "Destructive mutation")
            )
        assertNotNull(unauthorizedHigh)
        assertEquals(PermissionReviewOutcome.DENY, unauthorizedHigh?.outcome)

        val authorizedHigh =
            PermissionReviewResponsePolicy.parseAndEnforce(
                reviewTool("allow", "high", "medium", "Specifically requested")
            )
        assertNotNull(authorizedHigh)
        assertEquals(PermissionReviewOutcome.ALLOW, authorizedHigh?.outcome)

        val critical =
            PermissionReviewResponsePolicy.parseAndEnforce(
                reviewTool("allow", "critical", "high", "Irreversible broad action")
            )
        assertNotNull(critical)
        assertEquals(PermissionReviewOutcome.DENY, critical?.outcome)
    }

    @Test
    fun reviewerSubmissionToolPreservesQuotedNaturalLanguageRationale() {
        val decision =
            PermissionReviewResponsePolicy.parseAndEnforce(
                AITool(
                    name = "submit_permission_review",
                    parameters =
                        listOf(
                            ToolParameter("outcome", "deny"),
                            ToolParameter("risk_level", "high"),
                            ToolParameter("user_authorization", "low"),
                            ToolParameter(
                                "rationale",
                                "用户只说了\"试试一些高风险操作\"，没有授权写入 /system。",
                            ),
                        ),
                )
            )

        assertNotNull(decision)
        assertEquals(PermissionReviewOutcome.DENY, decision?.outcome)
        assertTrue(decision?.rationale?.contains("\"试试一些高风险操作\"") == true)
    }

    @Test
    fun reviewerSubmissionToolRejectsDuplicateOrIncompleteControlFields() {
        assertNull(
            PermissionReviewResponsePolicy.parseAndEnforce(
                AITool(
                    name = "submit_permission_review",
                    parameters =
                        listOf(
                            ToolParameter("outcome", "allow"),
                            ToolParameter("outcome", "deny"),
                            ToolParameter("risk_level", "low"),
                            ToolParameter("user_authorization", "high"),
                        ),
                )
            )
        )
        assertNull(
            PermissionReviewResponsePolicy.parseAndEnforce(
                AITool(
                    name = "submit_permission_review",
                    parameters =
                        listOf(
                            ToolParameter("outcome", "allow"),
                            ToolParameter("risk_level", "low"),
                        ),
                )
            )
        )
    }

    @Test
    fun reviewerResponseExtractorAcceptsOneResultToolAndRejectsMultipleCalls() = runBlocking {
        val single =
            """
            <tool name="submit_permission_review">
              <param name="outcome">allow</param>
              <param name="risk_level">low</param>
              <param name="user_authorization">low</param>
              <param name="rationale">Compatibility test</param>
            </tool>
            """.trimIndent()
        withoutAndroidLogging {
            assertEquals(
                PermissionReviewOutcome.ALLOW,
                PermissionReviewResponsePolicy.extractToolCallAndEnforce(single)?.outcome,
            )

            val duplicate = "$single\n$single"
            assertNull(PermissionReviewResponsePolicy.extractToolCallAndEnforce(duplicate))
            assertNull(
                PermissionReviewResponsePolicy.extractToolCallAndEnforce(
                    """{"outcome":"allow","risk_level":"low","user_authorization":"low","rationale":"JSON is not a tool call"}"""
                )
            )
        }
    }

    @Test
    fun reviewerResponseExtractorAllowsInvestigationBeforeOneFinalSubmission() = runBlocking {
        val response =
            """
            <tool name="inspect_permission_review_context">
              <param name="review_id">review-1</param>
              <param name="operation">git_context</param>
            </tool>
            <tool name="submit_permission_review">
              <param name="outcome">deny</param>
              <param name="risk_level">high</param>
              <param name="user_authorization">low</param>
              <param name="rationale">Investigation completed</param>
            </tool>
            """.trimIndent()

        withoutAndroidLogging {
            assertEquals(
                PermissionReviewOutcome.DENY,
                PermissionReviewResponsePolicy.extractToolCallAndEnforce(response)?.outcome,
            )
        }
    }

    @Test
    fun submissionRegistryAcceptsOnlyOneExecutedSubmissionForTheActiveReview() {
        val reviewId = "review-${System.nanoTime()}"
        val valid =
            tool(
                PermissionReviewSubmissionTool.NAME,
                "review_id" to reviewId,
                "outcome" to "deny",
                "risk_level" to "high",
                "user_authorization" to "low",
                "rationale" to "Denied",
            )
        val wrong = valid.copy(parameters = valid.parameters.map { parameter ->
            if (parameter.name == "review_id") parameter.copy(value = "wrong") else parameter
        })

        PermissionReviewSubmissionRegistry.register(reviewId)
        try {
            assertFalse(PermissionReviewSubmissionRegistry.submit("wrong", wrong))
            assertTrue(PermissionReviewSubmissionRegistry.submit(reviewId, valid))
            assertFalse(PermissionReviewSubmissionRegistry.submit(reviewId, valid))
            val consumed = PermissionReviewSubmissionRegistry.consume(reviewId)
            assertNotNull(consumed)
            assertEquals(
                PermissionReviewOutcome.DENY,
                PermissionReviewResponsePolicy.parseAndEnforce(
                    requireNotNull(consumed),
                    expectedReviewId = reviewId,
                )?.outcome,
            )
            assertNull(
                PermissionReviewResponsePolicy.parseAndEnforce(
                    valid,
                    expectedReviewId = "another-review",
                )
            )
        } finally {
            PermissionReviewSubmissionRegistry.unregister(reviewId)
        }
    }

    @Test
    fun permissionReviewerUsesFunctionalModelAndReusesTheSameConfigGate() {
        val configured = FunctionConfigMapping(configId = "reviewer", modelIndex = 2)

        assertEquals(
            PermissionReviewerModelSelection(
                configId = "reviewer",
                modelIndex = 2,
                reentrantParentModelConfigId = null,
            ),
            resolvePermissionReviewerModelSelection(
                configuredMapping = configured,
                parentModelConfigId = "chat",
                parentModelIndex = 0,
            ),
        )
        assertEquals(
            "reviewer",
            resolvePermissionReviewerModelSelection(
                    configuredMapping = configured,
                    parentModelConfigId = "reviewer",
                    parentModelIndex = 2,
                )
                .reentrantParentModelConfigId,
        )
        assertEquals(
            "reviewer",
            resolvePermissionReviewerModelSelection(
                    configuredMapping = configured,
                    parentModelConfigId = "reviewer",
                    parentModelIndex = 1,
                )
                .reentrantParentModelConfigId
        )
    }

    @Test
    fun canonicalActionFingerprintSeparatesCommandsButIgnoresPresentationIds() {
        val context = ToolPermissionReviewContext(workspacePath = "C:/workspace")
        val first =
            PermissionReviewAction.fromTool(
                tool("execute_shell", "command" to "echo first"),
                "run command",
                context,
                "call-a",
            )
        val same = first.copy(targetId = "call-b", summary = "different presentation")
        val second =
            PermissionReviewAction.fromTool(
                tool("execute_shell", "command" to "echo second"),
                "run command",
                context,
                "call-c",
            )

        assertEquals(first.fingerprint(), same.fingerprint())
        assertFalse(first.fingerprint() == second.fingerprint())

        val prefix = "a".repeat(8_100)
        val suffix = "z".repeat(8_100)
        val largeA =
            PermissionReviewAction.fromTool(
                tool("write_file", "content" to (prefix + "A" + suffix)),
                "write",
                context,
                "large-a",
            )
        val largeB =
            PermissionReviewAction.fromTool(
                tool("write_file", "content" to (prefix + "B" + suffix)),
                "write",
                context,
                "large-b",
            )
        assertFalse(largeA.fingerprint() == largeB.fingerprint())

        val reorderedA =
            PermissionReviewAction.fromTool(
                tool("execute_shell", "command" to "echo stable", "cwd" to "C:/workspace"),
                "run command",
                context,
                "order-a",
            )
        val reorderedB =
            PermissionReviewAction.fromTool(
                tool("execute_shell", "cwd" to "C:/workspace", "command" to "echo stable"),
                "run command",
                context,
                "order-b",
            )
        assertEquals(reorderedA.fingerprint(), reorderedB.fingerprint())
    }

    @Test
    fun reviewerTranscriptExcludesAssistantThinkingButKeepsVisibleAndUserText() {
        assertEquals(
            "Visible answer",
            permissionReviewTranscriptContent(
                    sender = "ai",
                    roleName = "Guardian",
                    content = "<think>private chain</think>Visible answer",
                )
                .trim(),
        )
        assertEquals(
            "User wrote <think>literally</think>",
            permissionReviewTranscriptContent(
                sender = "user",
                roleName = "user",
                content = "User wrote <think>literally</think>",
            ),
        )
    }

    @Test
    fun duplicateToolParametersAreRejectedBeforePermissionReview() {
        assertEquals(
            setOf("command"),
            findDuplicateToolParameterNames(
                AITool(
                    name = "execute_shell",
                    parameters =
                        listOf(
                            ToolParameter("command", "dangerous"),
                            ToolParameter("command", "harmless"),
                        ),
                )
            ),
        )
    }

    @Test
    fun exactOverrideIsAtomicAndReviewHostStillRejectsCriticalRisk() {
        PermissionReviewExactOverrideStore.record("chat", "fingerprint", "review")
        assertNotNull(PermissionReviewExactOverrideStore.reserve("chat", "fingerprint", "attempt"))
        assertNull(PermissionReviewExactOverrideStore.reserve("chat", "fingerprint", "parallel"))
        PermissionReviewExactOverrideStore.release("attempt")
        assertNotNull(PermissionReviewExactOverrideStore.reserve("chat", "fingerprint", "retry"))
        PermissionReviewExactOverrideStore.commit("retry")
        assertNull(PermissionReviewExactOverrideStore.reserve("chat", "fingerprint", "consumed"))

        val high =
            PermissionReviewResponsePolicy.parseAndEnforce(
                reviewTool("deny", "high", "low", "previously denied"),
                exactOverride = true,
            )
        assertEquals(PermissionReviewOutcome.DENY, high?.outcome)
        val reviewerAllow =
            PermissionReviewResponsePolicy.parseAndEnforce(
                reviewTool("allow", "high", "low", "user overrode exact action"),
                exactOverride = true,
            )
        assertEquals(PermissionReviewOutcome.ALLOW, reviewerAllow?.outcome)
        val critical =
            PermissionReviewResponsePolicy.parseAndEnforce(
                reviewTool("allow", "critical", "high", "critical"),
                exactOverride = true,
            )
        assertEquals(PermissionReviewOutcome.DENY, critical?.outcome)
    }

    @Test
    fun denialCircuitInterruptsOnThirdConsecutiveReviewDenial() {
        val chatId = "circuit-${System.nanoTime()}"
        val turnId = "turn"
        assertFalse(PermissionReviewCircuitBreaker.recordDenial(chatId, turnId).interruptTurn)
        assertFalse(PermissionReviewCircuitBreaker.recordDenial(chatId, turnId).interruptTurn)
        assertTrue(PermissionReviewCircuitBreaker.recordDenial(chatId, turnId).interruptTurn)
        assertTrue(PermissionReviewCircuitBreaker.isInterrupted(chatId, turnId))
    }

    @Test
    fun onlyHostInterruptSignalCancelsThePendingToolBatch() {
        val ordinaryDenial =
            ToolResult("shell", false, StringResultData(""), error = "denied")
        val circuitDenial = ordinaryDenial.copy(interruptTurn = true)

        assertFalse(shouldInterruptPendingToolBatch(listOf(ordinaryDenial)))
        assertTrue(shouldInterruptPendingToolBatch(listOf(ordinaryDenial, circuitDenial)))
    }

    @Test
    fun reviewInspectionIsCapabilityBoundAndWorkspaceScoped() {
        val workspace = Files.createTempDirectory("operit-review-inspection").toFile()
        val inside = File(workspace, "evidence.txt").apply { writeText("bounded evidence") }
        val outside = File(workspace.parentFile, "outside-${System.nanoTime()}.txt")
            .apply { writeText("secret") }
        val reviewId = PermissionReviewInspectionRegistry.newReviewId()
        val action =
            PermissionReviewAction.fromTool(
                tool("read_file", "path" to inside.path),
                "read evidence",
                ToolPermissionReviewContext(workspacePath = workspace.path),
                "target",
            )
        PermissionReviewInspectionRegistry.register(reviewId, workspace.path, null, action)
        try {
            assertTrue(
                PermissionReviewInspectionRegistry.inspect(reviewId, "read_text", inside.path)
                    .contains("bounded evidence")
            )
            assertTrue(
                PermissionReviewInspectionRegistry.inspect(reviewId, "read_text", outside.path)
                    .startsWith("Inspection rejected")
            )
            assertTrue(
                PermissionReviewInspectionRegistry.inspect("wrong", "read_text", inside.path)
                    .startsWith("Inspection rejected")
            )
        } finally {
            PermissionReviewInspectionRegistry.unregister(reviewId)
            outside.delete()
            inside.delete()
            workspace.delete()
        }
    }

    @Test
    fun virtualWorkspaceEnvironmentDisablesLocalInspection() {
        val reviewId = PermissionReviewInspectionRegistry.newReviewId()
        val action =
            PermissionReviewAction.fromTool(
                tool("read_file", "path" to "/sdcard/evidence.txt"),
                "read evidence",
                ToolPermissionReviewContext(workspacePath = "/", workspaceEnv = "repo:my-repo"),
                "target",
            )
        PermissionReviewInspectionRegistry.register(reviewId, "/", "repo:my-repo", action)
        try {
            val result =
                PermissionReviewInspectionRegistry.inspect(
                    reviewId,
                    "read_text",
                    "/sdcard/evidence.txt",
                )
            assertTrue(result.startsWith("Inspection rejected"))
            assertTrue(result.contains("not available for this workspace environment"))
        } finally {
            PermissionReviewInspectionRegistry.unregister(reviewId)
        }
    }

    private fun reviewTool(
        outcome: String,
        risk: String,
        authorization: String,
        rationale: String,
    ): AITool =
        tool(
            PermissionReviewSubmissionTool.NAME,
            "outcome" to outcome,
            "risk_level" to risk,
            "user_authorization" to authorization,
            "rationale" to rationale,
        )

    private fun tool(name: String, vararg parameters: Pair<String, String>): AITool =
        AITool(name = name, parameters = parameters.map { ToolParameter(it.first, it.second) })

    private fun workspaceAllows(
        tool: AITool,
        workspacePath: String,
        workspaceEnv: String? = null,
        terminalCurrentDirectory: String? = null,
    ): Boolean =
        WorkspaceToolPermissionPolicy.isAutoApproved(
            tool = tool,
            workspacePath = workspacePath,
            workspaceEnv = workspaceEnv,
            canonicalPathResolver = { path, _ ->
                runCatching { File(path).canonicalPath }.getOrNull()
            },
            terminalCurrentDirectory = terminalCurrentDirectory,
        )

    private suspend fun <T> withoutAndroidLogging(block: suspend () -> T): T =
        Mockito.mockStatic(AppLogger::class.java).use {
            try {
                StreamLogger.setEnabled(false)
                block()
            } finally {
                StreamLogger.setEnabled(true)
            }
        }
}
