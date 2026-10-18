package com.ai.assistance.operit.ui.features.orbit

import androidx.activity.compose.BackHandler
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
internal fun OrbitLearningPage(spaceId: String, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    // Kept outside the transition so coming back lands where the list was left.
    val homeList = rememberLazyListState()
    AnimatedContent(
        targetState = page,
        // Opaque, so the gap the pushed page has not covered yet never shows the page underneath.
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        transitionSpec = {
            // iOS push: the new page covers from the right while the list underneath drifts left,
            // so nothing behind this page shows through mid-swipe.
            if (targetState != null) {
                slideInHorizontally(tween(OrbitMotion.PUSH_MS, easing = OrbitMotion.Easing)) { it } togetherWith
                    slideOutHorizontally(tween(OrbitMotion.PUSH_MS, easing = OrbitMotion.Easing)) { -it / 4 }
            } else {
                slideInHorizontally(tween(OrbitMotion.PUSH_MS, easing = OrbitMotion.Easing)) { -it / 4 } togetherWith
                    slideOutHorizontally(tween(OrbitMotion.PUSH_MS, easing = OrbitMotion.Easing)) { it }
            }.apply { targetContentZIndex = if (targetState != null) 1f else 0f }
        },
        label = "orbitLearningPage",
    ) { shown ->
        val close = { page = null }
        when (shown) {
            null -> OrbitLearningHome(homeList, onBack) { page = it }
            "skills" -> OrbitAllSkillsPage(spaceId, close)
            else -> com.ai.assistance.operit.ui.features.memory.screens.MemoryLibraryDestination(shown, spaceId, close)
        }
    }
}

@Composable
private fun OrbitLearningHome(listState: LazyListState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    BackHandler(onBack = onBack)
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
private fun OrbitAllSkillsPage(spaceId: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
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
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}
