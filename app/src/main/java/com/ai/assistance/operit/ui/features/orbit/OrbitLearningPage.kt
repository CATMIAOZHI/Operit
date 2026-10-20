package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.skill.SkillRepository

private class LearningRow(val page: String, val title: Int, val description: Int, val icon: String)

// Memory and what Orbit knows about the user are the cards on the profile page, so they are not
// repeated here: this page is only how Orbit learns.
private val learningGroups = listOf(
    R.string.orbit_learning_skills_group to listOf(
        LearningRow("skills", R.string.memory_all_skills, R.string.orbit_learning_all_skills_desc, "folder-open"),
        LearningRow("learned", R.string.memory_learned_skills, R.string.memory_nav_learned, "wand-sparkles"),
        LearningRow("logs", R.string.memory_extraction_logs, R.string.memory_nav_logs, "scroll-text"),
    ),
    R.string.memory_group_review to listOf(
        LearningRow("review", R.string.memory_review_title, R.string.memory_nav_review, "list-checks"),
        LearningRow("history", R.string.chat_recall_title, R.string.memory_nav_history, "message-square-text"),
    ),
    R.string.memory_group_settings to listOf(
        LearningRow("automation", R.string.memory_automation_title, R.string.memory_nav_automation, "sliders-horizontal"),
    ),
)

/**
 * Orbit's "skills and learning": a short list of what it learned and how, in the profile's look.
 * Each row pushes its page from the right, like the other pages under the profile.
 */
@Composable
internal fun OrbitLearningPage(spaceId: String, onBack: () -> Unit, onDraft: (String) -> Unit) {
    // The open pages, top last, one per line: a library page, or the skills and a skill's history.
    var pages by rememberSaveable { mutableStateOf("") }
    val stack = remember(pages) { pages.split('\n').filter { it.isNotEmpty() } }
    var skillsReload by remember { mutableIntStateOf(0) }
    // Kept outside the transition so coming back lands where the list was left.
    val homeList = rememberLazyListState()
    // iOS push, with the swipe back following the finger. Opaque, so nothing behind shows through.
    OrbitPushHost(
        entries = stack,
        onPop = {
            if (stack.lastOrNull()?.startsWith(SKILL_HISTORY) == true) skillsReload++
            pages = stack.dropLast(1).joinToString("\n")
        },
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        base = { OrbitLearningHome(homeList, onBack) { pages = it } },
    ) { shown ->
        val close = {
            // Only while this page is still on top, so a late call never closes another page.
            val now = pages.split('\n').filter { it.isNotEmpty() }
            if (now.lastOrNull() == shown) {
                if (shown.startsWith(SKILL_HISTORY)) skillsReload++
                pages = now.dropLast(1).joinToString("\n")
            }
        }
        when {
            shown == "skills" -> OrbitAllSkillsPage(spaceId, close, onDraft, skillsReload) { name ->
                pages = (stack + (SKILL_HISTORY + name.replace('\n', ' '))).joinToString("\n")
            }
            shown.startsWith(SKILL_HISTORY) -> com.ai.assistance.operit.ui.features.memory.screens.dialogs.MemoryReviewDialog(
                spaceId, onDismiss = close, initialSkillName = shown.removePrefix(SKILL_HISTORY))
            else -> com.ai.assistance.operit.ui.features.memory.screens.MemoryLibraryDestination(shown, spaceId, close)
        }
    }
}

private const val SKILL_HISTORY = "skill-history:"

@Composable
private fun OrbitLearningHome(listState: LazyListState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    OrbitPageBack(onBack = onBack)
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding(),
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
    ) {
        item(key = "header") {
            OrbitPageHeader(stringResource(R.string.orbit_memory_row), onBack)
            Text(
                stringResource(R.string.orbit_learning_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
        }
        learningGroups.forEach { (title, rows) ->
            item(key = "group-$title") {
                SectionTitle(stringResource(title))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    Column {
                        rows.forEachIndexed { index, row ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 70.dp, end = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                            LearningRowItem(row) { onOpen(row.page) }
                        }
                    }
                }
            }
        }
    }
}

/** The round back button and the page title, as on Orbit's other pages. */
@Composable
internal fun OrbitPageHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(bottom = 8.dp)) {
        OrbitCircleButton(orbitIcon("chevron-left"), stringResource(R.string.back), onBack)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LearningRowItem(row: LearningRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(orbitIcon(row.icon), contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(row.title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                stringResource(row.description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            orbitIcon("chevron-right"),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 8.dp).size(20.dp),
        )
    }
}

/** Every skill Orbit can load, in folders by where it came from, under the same header. */
@Composable
private fun OrbitAllSkillsPage(
    spaceId: String,
    onBack: () -> Unit,
    onDraft: (String) -> Unit,
    reloadSignal: Int,
    onOpenHistory: (String) -> Unit,
) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val repository by produceState<SkillRepository?>(null, spaceId) {
        value = SkillRepository.forMemorySpace(context, spaceId)
    }
    val snackbar = remember { SnackbarHostState() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            OrbitPageHeader(
                stringResource(R.string.memory_all_skills), onBack,
                Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
            // The add button sits at the bottom of this box, so it must clear the navigation bar.
            Box(Modifier.weight(1f).padding(horizontal = 12.dp).navigationBarsPadding()) {
                val loaded = repository
                if (loaded == null) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else loaded.let {
                    com.ai.assistance.operit.ui.features.packages.screens.SkillConfigScreen(
                        it, snackbar, groupBySource = true, showHeader = false,
                        onDraftNewSkill = onDraft,
                        onOpenSkillHistory = onOpenHistory,
                        reloadSignal = reloadSignal,
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}
