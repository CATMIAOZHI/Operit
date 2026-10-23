package com.ai.assistance.operit.ui.features.packages.screens

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.skill.SkillPackage
import com.ai.assistance.operit.data.skill.SkillRepository
import com.ai.assistance.operit.ui.common.displays.MarkdownTextComposable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun SkillConfigScreen(
    skillRepository: SkillRepository,
    snackbarHostState: SnackbarHostState,
    searchQuery: String = "",
    skillOrder: List<String> = emptyList(),
    onSaveSkillOrder: (List<String>) -> Unit = {},
    learnedOnlyProfileId: String? = null,
    /** Shows the skills in folders by origin (learned, added by the user, built in), for an Orbit. */
    groupBySource: Boolean = false,
    /** False when the page around it already has a title, as Orbit's all-skills page does. */
    showHeader: Boolean = true,
    /** Set for an Orbit: new skills are asked of Orbit in chat, so the add button drafts a prompt. */
    onDraftNewSkill: ((String) -> Unit)? = null,
    /** Set when the page around opens a skill's history as its own full page. */
    onOpenSkillHistory: ((String) -> Unit)? = null,
    /** Changes when the list should be read again, as after a skill's history page closes. */
    reloadSignal: Int = 0,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val skillVisibilityPreferences = skillRepository.skillVisibilityPreferences

    var skills by remember { mutableStateOf<Map<String, SkillPackage>>(emptyMap()) }
    var learnedNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var archivedNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }

    var selectedSkill by remember { mutableStateOf<SkillPackage?>(null) }
    var historySkill by remember { mutableStateOf<String?>(null) }
    var selectedSkillDetail by remember { mutableStateOf<SkillDetailDialogData?>(null) }
    var isSkillDetailLoading by remember { mutableStateOf(false) }
    var skillLoadErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showSkillLoadErrorsDialog by remember { mutableStateOf(false) }

    var showImportDialog by remember { mutableStateOf(false) }
    var importTabIndex by remember { mutableStateOf(0) }
    var repoUrlInput by remember { mutableStateOf("") }
    var zipUri by remember { mutableStateOf<Uri?>(null) }
    var zipFileName by remember { mutableStateOf("") }
    var manualSkillId by remember { mutableStateOf("") }
    var manualSkillDescription by remember { mutableStateOf("") }
    var manualSkillContent by remember { mutableStateOf("") }
    var manualAttachments by remember { mutableStateOf<List<ManualImportAttachment>>(emptyList()) }
    var isImporting by remember { mutableStateOf(false) }
    // Shown inside the import sheet; a snackbar would sit behind it.
    var importError by remember { mutableStateOf<String?>(null) }
    var skillDetailError by remember { mutableStateOf<String?>(null) }
    var deletingSkill by remember { mutableStateOf(false) }
    // Each skill's "visible to the AI" switch, read once off the main thread for the whole list.
    var visibleToAi by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    // Only the newest scan lands; a switch flipped during a scan keeps the user's choice.
    val refreshGen = remember { java.util.concurrent.atomic.AtomicInteger() }
    val toggleGen = remember { java.util.concurrent.atomic.AtomicInteger() }
    val setVisible: (String, Boolean) -> Unit = { name, visible ->
        toggleGen.incrementAndGet()
        visibleToAi = visibleToAi + (name to visible)
        skillVisibilityPreferences.setSkillVisibleToAi(name, visible)
    }
    val learnedRepository = remember(skillRepository.memorySpaceId) {
        com.ai.assistance.operit.data.preferences.LearnedSkillRepository(context, skillRepository.memorySpaceId)
    }
    suspend fun readSwitches(names: Collection<String>) = withContext(Dispatchers.IO) {
        names.associateWith { skillVisibilityPreferences.isSkillVisibleToAi(it) }
    }

    /** [quiet] re-reads in the background without the loading spinner, for coming back to the page. */
    suspend fun refreshSkills(quiet: Boolean = false) {
        val gen = refreshGen.incrementAndGet()
        val toggles = toggleGen.get()
        if (!quiet) isLoading = true
        try {
            val loaded =
                withContext(Dispatchers.IO) {
                    skillRepository.getAvailableSkillPackagesSnapshot()
                }
            // Folders are decided by these names, so they land together with the list. Only the
            // learned-only view and Orbit's folders use them, and they reuse the scan just made.
            val owned = if (learnedOnlyProfileId != null || groupBySource)
                learnedRepository.owned(learnedOnlyProfileId, loaded.first) else emptySet()
            val archived = if (groupBySource) learnedRepository.archived() else archivedNames
            val switches = readSwitches(loaded.first.keys)
            if (gen != refreshGen.get()) return
            skills = loaded.first
            learnedNames = owned
            archivedNames = archived
            visibleToAi = if (toggles == toggleGen.get()) switches
                else switches + visibleToAi.filterKeys { it in switches }
            skillLoadErrors = loaded.second
        } finally {
            // The newest scan ends the spinner, even when a quiet one replaced a loud one.
            if (gen == refreshGen.get()) isLoading = false
        }
    }

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            zipUri = it
            zipFileName = resolveUriDisplayName(context, it, fallback = "skill.zip")
        }
    }

    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult

        val existingKeys = manualAttachments.map { it.uri.toString() }.toMutableSet()
        val updated = manualAttachments.toMutableList()
        uris.forEach { uri ->
            if (existingKeys.add(uri.toString())) {
                updated += ManualImportAttachment(
                    uri = uri,
                    displayName = resolveUriDisplayName(context, uri)
                )
            }
        }
        manualAttachments = updated
    }

    LaunchedEffect(Unit) {
        refreshSkills()
    }
    // A restore on a skill's history page may change it: read the list again, quietly.
    LaunchedEffect(reloadSignal) {
        if (reloadSignal > 0) runCatching { refreshSkills(quiet = true) }
    }
    // While the page is in the background, Orbit may learn or drop skills, and a sweep may archive
    // them. Coming back re-reads an Orbit's list quietly. The plain skill page only re-reads the
    // switches, since nothing changes its folders behind its back.
    var resumedOnce by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (resumedOnce && (groupBySource || learnedOnlyProfileId != null)) scope.launch {
            runCatching { refreshSkills(quiet = true) }
        } else if (resumedOnce && skills.isNotEmpty()) scope.launch {
            val names = skills.keys
            val archived = if (groupBySource) runCatching { learnedRepository.archived() }.getOrDefault(archivedNames) else archivedNames
            val switches = readSwitches(names)
            if (skills.keys == names) {
                archivedNames = archived
                visibleToAi = switches
            }
        }
        resumedOnce = true
    }
    // Switching a learned skill back on for the AI also takes it out of the archive and restarts
    // its idle clock, wherever the switch is (here or on the learned-skills page).
    val markShown: (String) -> Unit = { name ->
        scope.launch {
            runCatching {
                com.ai.assistance.operit.data.preferences.LearnedSkillRepository(context, skillRepository.memorySpaceId)
                    .unarchive(name)
            }
            archivedNames = archivedNames - name
        }
    }

    val openSkill: (SkillPackage) -> Unit = { skill ->
        val targetPath = skill.directory.absolutePath
        selectedSkill = skill
        selectedSkillDetail = null
        skillDetailError = null
        isSkillDetailLoading = true
        scope.launch {
            val detail = try {
                withContext(Dispatchers.IO) {
                    buildSkillDetailDialogData(skill = skill, skillContent = skillRepository.readSkillContent(skill.name).orEmpty())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (selectedSkill?.directory?.absolutePath == targetPath) {
                    skillDetailError = context.getString(R.string.skill_detail_load_failed, e.message ?: e.javaClass.simpleName)
                    isSkillDetailLoading = false
                }
                return@launch
            }
            if (selectedSkill?.directory?.absolutePath == targetPath) {
                selectedSkillDetail = detail
                isSkillDetailLoading = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            if (learnedOnlyProfileId == null && showHeader) {
            // Gray card with a round icon, like the settings pages.
            Surface(shape = SkillCard, color = skillCardColor(), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkillRoundIcon("puzzle", size = 44.dp, iconSize = 22.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.skills), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            // An Orbit's folder path means nothing to the user; the count does.
                            text = if (groupBySource) stringResource(R.string.skill_folder_total, skills.size)
                                else skillRepository.getSkillsDirectoryPath(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            refreshSkills()
                            snackbarHostState.showSnackbar(context.getString(R.string.skillmgr_refreshed))
                        }
                    }) {
                        Icon(skillIcon("refresh-cw"), stringResource(R.string.refresh), Modifier.size(20.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            }

            val displayedSkills =
                remember(skills, searchQuery, skillOrder, learnedNames, learnedOnlyProfileId) {
                    val searchText = searchQuery.trim()
                    val filtered = skills.values
                        .filter { learnedOnlyProfileId==null || it.name in learnedNames }
                        .filter { skill ->
                            searchText.isEmpty() ||
                                skill.name.contains(searchText, ignoreCase = true) ||
                                skill.description.contains(searchText, ignoreCase = true) ||
                                skill.directory.absolutePath.contains(searchText, ignoreCase = true)
                        }
                    if (searchText.isEmpty() && skillOrder.isNotEmpty()) {
                        val orderIndex = skillOrder.withIndex().associate { (i, name) -> name to i }
                        filtered.sortedBy { orderIndex[it.name] ?: Int.MAX_VALUE }
                    } else {
                        filtered.sortedBy { it.name }
                    }
                }

            // The learned-only view has no import action, so the shared "look in this
            // directory" wording would be a dead end there.
            val learnedEmpty = stringResource(R.string.memory_learned_empty)
            if (skills.isEmpty() && isLoading) {
                // The spinner below covers the first load; an empty-state line under it would flash.
            } else if (skills.isEmpty()) {
                SkillEmptyHint(
                    text = if (learnedOnlyProfileId != null) {
                        learnedEmpty
                    } else if (groupBySource) {
                        stringResource(R.string.orbit_skills_empty)
                    } else {
                        stringResource(R.string.skillmgr_no_skills_found, skillRepository.getSkillsDirectoryPath())
                    }
                )
            } else if (groupBySource && displayedSkills.isEmpty()) {
                SkillEmptyHint(stringResource(R.string.no_matching_skills_found), icon = "search-x")
            } else if (groupBySource) {
                SkillSourceFolders(
                    skills = displayedSkills,
                    learnedNames = learnedNames,
                    archivedNames = archivedNames,
                    builtInNames = remember { com.ai.assistance.operit.features.orbit.OrbitBundledSkills.NAMES.toSet() },
                    searching = searchQuery.isNotBlank(),
                    visibleToAi = visibleToAi,
                    onVisibleChange = setVisible,
                    onOpen = openSkill,
                    onRestore = { name ->
                        scope.launch {
                            val restored = runCatching {
                                com.ai.assistance.operit.data.preferences.LearnedSkillRepository(context, skillRepository.memorySpaceId)
                                    .unarchive(name)
                            }.isSuccess
                            if (restored) {
                                setVisible(name, true)
                                archivedNames = archivedNames - name
                            }
                            snackbarHostState.showSnackbar(context.getString(
                                if (restored) R.string.skill_restored else R.string.skill_restore_failed, name))
                        }
                    },
                    onShown = markShown,
                )
            } else {
                var orderedSkills by remember(displayedSkills) {
                    mutableStateOf(displayedSkills)
                }
                val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
                val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                    orderedSkills = orderedSkills.toMutableList().apply {
                        add(to.index, removeAt(from.index))
                    }
                    val newOrder = orderedSkills.map { it.name }
                    onSaveSkillOrder(newOrder)
                }

                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    // One gray card: rows touch, with thin inset lines between them.
                    contentPadding = PaddingValues(bottom = 120.dp)
                ) {
                    if (orderedSkills.isEmpty()) {
                        item(key = "empty_skill_search_state") {
                            SkillEmptyHint(
                                text = if (learnedOnlyProfileId != null && searchQuery.isBlank()) {
                                    learnedEmpty
                                } else {
                                    stringResource(R.string.no_matching_skills_found)
                                },
                                icon = if (learnedOnlyProfileId != null && searchQuery.isBlank()) "puzzle" else "search-x",
                            )
                        }
                    }

                    itemsIndexed(
                        items = orderedSkills,
                        key = { _, skill -> skill.name },
                        contentType = { _, _ -> "skill" }
                    ) { index, skill ->
                        ReorderableItem(
                            reorderableState,
                            key = skill.name,
                            animateItemModifier = Modifier.animateItem(
                            fadeInSpec = spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                            placementSpec = spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                            fadeOutSpec = spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)
                        )
                        ) { isDragging ->
                            val last = orderedSkills.lastIndex
                            val shape = when {
                                isDragging || last == 0 -> SkillCard
                                index == 0 -> SkillCardTop
                                index == last -> SkillCardBottom
                                else -> RectangleShape
                            }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (isDragging) Modifier.shadow(8.dp, SkillCard) else Modifier),
                                color = if (isDragging) MaterialTheme.colorScheme.surface else skillCardColor(),
                                shape = shape
                            ) {
                                Column {
                                    SkillListItem(
                                        skill = skill,
                                        visibleToAi = visibleToAi[skill.name] ?: true,
                                        onVisibleChange = { setVisible(skill.name, it) },
                                        onShown = { markShown(skill.name) },
                                        onClick = { openSkill(skill) },
                                        modifier = Modifier.longPressDraggableHandle()
                                    )
                                    if (index < last && !isDragging) {
                                        HorizontalDivider(
                                            Modifier.padding(start = 60.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Round pills: a soft red one when some skills failed to load, a dark one to add.
            if (skillLoadErrors.isNotEmpty()) {
                SkillPill(
                    text = stringResource(R.string.error_occurred_simple),
                    icon = "circle-alert",
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) { showSkillLoadErrorsDialog = true }
            }

            if (learnedOnlyProfileId == null) SkillPill(
                text = stringResource(R.string.skill_import_title),
                icon = "plus",
                color = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
            ) {
                if (onDraftNewSkill != null) onDraftNewSkill(context.getString(R.string.orbit_skill_new_draft))
                else showImportDialog = true
            }
        }

        if (skills.isEmpty() && (isLoading || isImporting)) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            }
        }
    }

    // Problems stay inside the sheet; on success it closes and the result shows below.
    val runImport: () -> Unit = {
        scope.launch {
            val tab = importTabIndex
            importError = null
            try {
                val result = when (tab) {
                    0 -> {
                        val url = repoUrlInput.trim()
                        if (url.isBlank()) {
                            importError = context.getString(R.string.enter_repo_info)
                            return@launch
                        }
                        isImporting = true
                        skillRepository.importSkillFromGitHubRepo(url)
                    }
                    1 -> {
                        val uri = zipUri
                        if (uri == null) {
                            importError = context.getString(R.string.select_zip_file)
                            return@launch
                        }
                        // The picker's display name may hold a path; keep only the file name.
                        val nameToUse = zipFileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "skill.zip" }
                        if (!nameToUse.endsWith(".zip", ignoreCase = true)) {
                            importError = context.getString(R.string.skillmgr_only_zip_files)
                            return@launch
                        }
                        isImporting = true
                        withContext(Dispatchers.IO) {
                            val tempFile = File(context.cacheDir, nameToUse)
                            try {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    tempFile.outputStream().use { output -> input.copyTo(output) }
                                } ?: throw IllegalStateException(context.getString(R.string.skillmgr_cannot_read_file))
                                skillRepository.importSkillFromZip(tempFile)
                            } finally {
                                runCatching { tempFile.delete() }
                            }
                        }
                    }
                    else -> {
                        val skillId = manualSkillId.trim()
                        val skillContent = manualSkillContent.trim()
                        val problem = when {
                            skillId.isBlank() -> R.string.skillmgr_direct_skill_id_required
                            !isValidSkillId(skillId) -> R.string.skillmgr_direct_skill_id_invalid
                            skillContent.isBlank() -> R.string.skillmgr_direct_content_required
                            else -> null
                        }
                        if (problem != null) {
                            importError = context.getString(problem)
                            return@launch
                        }
                        isImporting = true
                        skillRepository.importSkillFromDirectInput(
                            skillId = skillId,
                            description = manualSkillDescription.trim(),
                            content = skillContent,
                            attachmentUris = manualAttachments.map { it.uri }
                        )
                    }
                }
                refreshSkills()
                showImportDialog = false
                when (tab) {
                    0 -> repoUrlInput = ""
                    1 -> { zipUri = null; zipFileName = "" }
                    else -> {
                        manualSkillId = ""
                        manualSkillDescription = ""
                        manualSkillContent = ""
                        manualAttachments = emptyList()
                    }
                }
                isImporting = false
                snackbarHostState.showSnackbar(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                isImporting = false
                importError = context.getString(R.string.skillmgr_import_failed, e.message ?: "")
            } finally {
                isImporting = false
            }
        }
    }

    if (showImportDialog) {
        SkillImportSheet(
            tabIndex = importTabIndex,
            onTabChange = { importTabIndex = it; importError = null },
            repoUrl = repoUrlInput,
            onRepoUrlChange = { repoUrlInput = it },
            zipFileName = zipFileName,
            onPickZip = { zipPicker.launch("application/zip") },
            skillId = manualSkillId,
            onSkillIdChange = { manualSkillId = it },
            description = manualSkillDescription,
            onDescriptionChange = { manualSkillDescription = it },
            content = manualSkillContent,
            onContentChange = { manualSkillContent = it },
            attachments = manualAttachments.map { it.displayName },
            onAddAttachment = { attachmentPicker.launch(arrayOf("*/*")) },
            onRemoveAttachment = { index -> manualAttachments = manualAttachments.filterIndexed { i, _ -> i != index } },
            importing = isImporting,
            error = importError,
            onImport = runImport,
            onDismiss = { if (!isImporting) { showImportDialog = false; importError = null } },
        )
    }

    if (showSkillLoadErrorsDialog) {
        SkillLoadErrorsDialog(
            errors = skillLoadErrors,
            onDismiss = { showSkillLoadErrorsDialog = false }
        )
    }

    selectedSkill?.let { skill ->
        SkillDetailSheet(
            skill = skill,
            detail = selectedSkillDetail,
            isLoading = isSkillDetailLoading,
            // An Orbit's folders mean nothing to the user, wherever its skills are listed.
            showLocation = !groupBySource && skillRepository.memorySpaceId?.let {
                com.ai.assistance.operit.data.preferences.MemorySearchSettingsPreferences.isOrbitSpace(it)
            } != true,
            loadError = skillDetailError,
            deleting = deletingSkill,
            // An Orbit keeps no change review or rollback for its skills, so it has no history button.
            onHistory = if (skillRepository.memorySpaceId?.let {
                    !com.ai.assistance.operit.data.preferences.MemorySearchSettingsPreferences.isOrbitSpace(it)
                } == true) ({
                if (onOpenSkillHistory != null) onOpenSkillHistory(skill.name) else historySkill = skill.name
                selectedSkill = null
            }) else null,
            onDismiss = {
                selectedSkill = null
                selectedSkillDetail = null
                skillDetailError = null
                isSkillDetailLoading = false
            },
            onDelete = {
                val skillName = skill.name
                if (deletingSkill) return@SkillDetailSheet
                deletingSkill = true
                scope.launch {
                    val ok = try {
                        withContext(Dispatchers.IO) { skillRepository.deleteSkill(skillName) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    } finally {
                        deletingSkill = false
                    }
                    // The sheet stays up while the files go, then closes with the result below it.
                    selectedSkill = null
                    selectedSkillDetail = null
                    isSkillDetailLoading = false
                    if (ok) runCatching { refreshSkills() }
                    snackbarHostState.showSnackbar(context.getString(
                        if (ok) R.string.skillmgr_deleted else R.string.skillmgr_delete_failed, skillName))
                }
            }
        )
    }
    // A skill's history is its own page over the list, so back leaves the history, not the list.
    var shownHistorySkill by remember { mutableStateOf(historySkill) }
    LaunchedEffect(historySkill) { historySkill?.let { shownHistorySkill = it } }
    val closeHistory = { historySkill = null; scope.launch { refreshSkills() }; Unit }
    com.ai.assistance.operit.ui.features.orbit.OrbitCoverPage(visible = historySkill != null, onDismiss = closeHistory) {
        (historySkill ?: shownHistorySkill)?.let { name ->
            skillRepository.memorySpaceId?.let { space ->
                androidx.compose.runtime.key(name) {
                    com.ai.assistance.operit.ui.features.memory.screens.dialogs.MemoryReviewDialog(
                        space, onDismiss = closeHistory, initialSkillName = name)
                }
            }
        }
    }
}

private data class ManualImportAttachment(
    val uri: Uri,
    val displayName: String
)

internal data class SkillDetailDialogData(
    val skillContent: String,
    val directoryPath: String,
    val skillFilePath: String,
    val fileCount: Int,
    val folderCount: Int,
    val directoryPreview: String,
    val hiddenEntryCount: Int
)

private val SKILL_ID_PATTERN = Regex("^[A-Za-z0-9._-]+$")

private fun isValidSkillId(skillId: String): Boolean {
    return SKILL_ID_PATTERN.matches(skillId) && skillId != "." && skillId != ".."
}

private fun resolveUriDisplayName(context: Context, uri: Uri, fallback: String = "file"): String {
    val resolver = context.contentResolver
    val displayName = runCatching {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        }
    }.getOrNull()

    if (!displayName.isNullOrBlank()) {
        return displayName
    }

    val uriFallback = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    return uriFallback.ifBlank { fallback }
}

private fun buildSkillDetailDialogData(
    skill: SkillPackage,
    skillContent: String
): SkillDetailDialogData {
    val previewLines = mutableListOf<String>()
    var fileCount = 0
    var folderCount = 0
    val previewLimit = 18

    fun sortedChildren(parent: File): List<File> {
        return parent.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
    }

    fun addPreviewLine(depth: Int, label: String) {
        if (previewLines.size < previewLimit) {
            previewLines += "${"  ".repeat(depth)}$label"
        }
    }

    fun walk(node: File, depth: Int) {
        if (node.isDirectory) {
            folderCount += 1
            addPreviewLine(depth, "${node.name}/")
            sortedChildren(node).forEach { child ->
                walk(child, depth + 1)
            }
        } else {
            fileCount += 1
            addPreviewLine(depth, node.name)
        }
    }

    sortedChildren(skill.directory).forEach { child ->
        walk(child, depth = 0)
    }

    val totalEntries = fileCount + folderCount
    return SkillDetailDialogData(
        skillContent = skillContent,
        directoryPath = skill.directory.absolutePath,
        skillFilePath = skill.skillFile.absolutePath,
        fileCount = fileCount,
        folderCount = folderCount,
        directoryPreview = previewLines.joinToString("\n"),
        hiddenEntryCount = (totalEntries - previewLines.size).coerceAtLeast(0)
    )
}

@Composable
private fun SkillLoadErrorsDialog(
    errors: Map<String, String>,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.error_occurred_simple)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(scrollState)
            ) {
                errors.toSortedMap().forEach { (skillFolderName, errorText) ->
                    Text(
                        text = skillFolderName,
                        style = MaterialTheme.typography.titleSmall,
                        color = com.ai.assistance.operit.ui.common.readableErrorColor()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.ok))
            }
        }
    )
}

@Composable
private fun SkillListItem(
    skill: SkillPackage,
    visibleToAi: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onShown: () -> Unit = {},
) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkillRoundIcon("puzzle")
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (skill.description.isNotBlank()) {
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            val switchLabel = stringResource(R.string.skill_visible_to_ai, skill.name)
            Switch(
                modifier = Modifier.semantics { contentDescription = switchLabel },
                checked = visibleToAi,
                onCheckedChange = { checked ->
                    onVisibleChange(checked)
                    if (checked) onShown()
                }
            )
        }
    }
}

private val SkillCard = RoundedCornerShape(20.dp)
private val SkillCardTop = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private val SkillCardBottom = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)

@Composable
private fun skillCardColor() = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)

private fun skillIcon(name: String): ImageVector =
    com.ai.assistance.operit.ui.common.dynamicui.CardIcons.get("lucide:$name") ?: Icons.Filled.Build

@Composable
private fun SkillRoundIcon(icon: String, size: androidx.compose.ui.unit.Dp = 36.dp, iconSize: androidx.compose.ui.unit.Dp = 18.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        Icon(skillIcon(icon), null, Modifier.size(iconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SkillEmptyHint(text: String, icon: String = "puzzle") {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkillRoundIcon(icon, size = 56.dp, iconSize = 26.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun SkillPill(
    text: String,
    icon: String,
    color: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = CircleShape, color = color, contentColor = contentColor, shadowElevation = 6.dp,
        modifier = Modifier.heightIn(min = 52.dp)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(skillIcon(icon), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
