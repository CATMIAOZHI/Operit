package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import com.ai.assistance.operit.data.model.applyTo
import com.ai.assistance.operit.data.model.toChatMessage
import kotlinx.coroutines.ensureActive

/** The models before and after a reply that a different model wrote than the reply before it. */
internal data class ModelSwitch(val from: String, val to: String)

/** Who wrote a reply: its provider ("TYPE/config name", or older just "TYPE") and model. */
internal data class ReplyModel(val provider: String, val modelName: String)

/**
 * The name a read-only subagent chat shows its own replies under, in place of the empty name or
 * "Operit" they are stored with; null in other chats.
 */
val LocalSubagentTranscriptRole = androidx.compose.runtime.compositionLocalOf<String?> { null }

/**
 * What the replies before a page were read for: the chat, how it is drawn, the page and its
 * roles, whether the page starts a turn, and the name a read-only subagent chat shows its own
 * replies under (stored with no name or as "Operit").
 */
internal data class ModelSeedRequest(
    val chatId: String,
    val orbit: Boolean,
    val before: Long,
    val roles: Set<String>,
    val pageOpensTurn: Boolean,
    val subagentRole: String?,
)

/**
 * The replies before a page, by role, and the roles left unsettled (all of them when reading
 * took too long or failed).
 */
internal class ModelSeed(val request: ModelSeedRequest, val byRole: Map<String, ReplyModel>, val unsettled: Set<String>)

/** A line as last worked out, with the reply it sat on, so a changed reply does not reuse it. */
internal data class SettledSwitch(val reply: String, val switch: ModelSwitch)

/** Which reply a line belongs to: its role, the shown variant and the model that wrote it. */
internal fun settledReplyKey(message: ChatMessage): String =
    "${message.roleName.trim()}|${message.selectedVariantIndex}|${message.provider}|${message.modelName}"

/**
 * The lines last worked out for each recently open chat, by reply time, kept while the app runs:
 * coming back to a chat at a reply in the middle shows its line at once instead of after the
 * older replies are read again, which would push the reply down.
 */
internal object SettledModelSwitches {
    private val chats = object : LinkedHashMap<String, HashMap<Long, SettledSwitch>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HashMap<Long, SettledSwitch>>?) = size > 20
    }

    /** The lines of [key] (the chat and how it is drawn); only touched from the main thread. */
    fun of(key: String): HashMap<Long, SettledSwitch> = chats.getOrPut(key) { HashMap() }

    /** One read of the replies before a page at a time. */
    val reads = kotlinx.coroutines.sync.Semaphore(1)

    /** Past this many lines kept for one chat, those not on the page in view are let go. */
    const val PER_CHAT_LIMIT = 300
}

/**
 * The replies, by index, written by another model than the same role's shown reply before them.
 * Each reply keeps the provider and model that wrote it, so older chats show their switches as
 * well. In a group chat every member may have its own model, so each is compared with itself only.
 * A reply without a model name (from before names were kept) neither starts nor ends a switch.
 * The provider is saved as "TYPE/config name", so only its type counts: renaming a config, or
 * another account of the same service, is not a switch. A reply without a provider matches any.
 * [seedByRole] holds, per role, the reply just before [messages] when older ones are not loaded.
 */
internal fun modelSwitchesIn(
    messages: List<ChatMessage>,
    hidden: ((ChatMessage) -> Boolean)? = null,
    seedByRole: Map<String, ReplyModel> = emptyMap(),
): Map<Int, ModelSwitch> {
    val previousByRole = HashMap<String, ReplyModel>(seedByRole)
    val switches = HashMap<Int, ModelSwitch>()
    messages.forEachIndexed { index, message ->
        if (message.sender != "ai" || message.modelName.isBlank() || hidden?.invoke(message) == true ||
            message.displayMode.isCollaborationEvent || message.displayMode == ChatMessageDisplayMode.HIDDEN_PLACEHOLDER
        ) return@forEachIndexed
        val role = message.roleName.trim()
        val now = ReplyModel(message.provider, message.modelName)
        val before = previousByRole[role]
        if (before != null) {
            val sameName = before.modelName == now.modelName
            val typeBefore = providerType(before)
            val typeNow = providerType(now)
            val sameType = typeBefore.isEmpty() || typeNow.isEmpty() || typeBefore == typeNow
            if (!sameName || !sameType) {
                // The same model through another provider needs the provider to tell them apart.
                switches[index] = ModelSwitch(modelLabel(before, sameName), modelLabel(now, sameName))
            }
        }
        previousByRole[role] = now
    }
    return switches
}

private fun providerType(model: ReplyModel): String =
    model.provider.substringBefore('/').trim().uppercase()

/**
 * Whether [role] (as shown) also stands for replies stored with no name or as "Operit" (any case),
 * which a read-only subagent chat shows under its own name.
 */
private fun ModelSeedRequest.takesDefaultName(role: String): Boolean = subagentRole != null && role == subagentRole

/** The role a stored reply shows under, the other way round from [takesDefaultName]. */
private fun ModelSeedRequest.shownRole(stored: String): String =
    if (subagentRole != null && (stored.isEmpty() || stored.equals("Operit", ignoreCase = true))) subagentRole else stored

/**
 * Per role on the page, the model of the newest reply before it that the chat draws, and the
 * roles that could not be settled (they have replies before the page, but not within the reads
 * allowed), whose first line on the page is left as it was.
 */
internal suspend fun readReplyModelsBefore(
    dao: com.ai.assistance.operit.data.dao.ChatContentDao,
    request: ModelSeedRequest,
): Pair<Map<String, ReplyModel>, Set<String>> {
    if (!request.orbit) {
        val found = LinkedHashMap<String, ReplyModel>()
        for (role in request.roles) {
            val row = dao.replyModelsBefore(request.chatId, role, request.takesDefaultName(role), request.before, 1).firstOrNull() ?: continue
            found[role] = ReplyModel(row.provider, row.modelName)
        }
        return found to emptySet()
    }
    return orbitReplyModelsBefore(dao, request)
}

/**
 * For an Orbit chat, per role on the page, the model of the newest reply before it that the
 * chat draws. Orbit hides thinking and tool steps and moves a turn's cards to its last reply, so
 * older rows are read back a batch at a time, a whole turn at a time, through the same
 * [OrbitSpeech.history] and [OrbitSpeech.hidden] the page uses, until each role is found or the
 * chat begins. A page that starts inside a turn ([ModelSeedRequest.pageOpensTurn] false) gets
 * that turn's cards on its own rows, as when the page is drawn. A role with no reply at all
 * before the page is not looked for, and the walk stops after [ORBIT_SEED_ROW_BUDGET] rows or
 * [ORBIT_SEED_BYTE_BUDGET] bytes of text: the roles not found by then are returned as unsettled.
 */
private suspend fun orbitReplyModelsBefore(
    dao: com.ai.assistance.operit.data.dao.ChatContentDao,
    request: ModelSeedRequest,
): Pair<Map<String, ReplyModel>, Set<String>> {
    val chatId = request.chatId
    val found = LinkedHashMap<String, ReplyModel>()
    // Cheap, text-free check first, so a role new on this page does not read the whole chat.
    val wanted = request.roles.filterTo(HashSet()) {
        dao.replyModelsBefore(chatId, it, request.takesDefaultName(it), request.before, 1).isNotEmpty()
    }
    if (wanted.isEmpty()) return found to emptySet()
    var cursor = request.before
    var scanned = 0
    var bytes = 0L
    var complete = false
    // Rows of a turn whose user row is older than what has been read yet.
    var carry: List<ChatMessage> = emptyList()
    var newestTurn = true
    while (found.size < wanted.size) {
        kotlin.coroutines.coroutineContext.ensureActive()
        // Out of budget: a turn not read to its start may miss cards that would show one of its
        // rows, so it is not guessed at; its roles stay unsettled rather than given a wrong line.
        if (scanned >= ORBIT_SEED_ROW_BUDGET) break
        // Text is sized before it is read, so a turn of long tool output is not loaded at all.
        bytes += dao.contentBytesBefore(chatId, cursor, 40)
        if (bytes > ORBIT_SEED_BYTE_BUDGET) break
        val batch = dao.getMessagesForChatBeforeTimestampExclusiveDesc(chatId, cursor, 40)
        scanned += batch.size
        val rows: List<ChatMessage>
        if (batch.isEmpty()) {
            // The chat begins here, so the rows left are a whole turn.
            complete = true
            if (carry.isEmpty()) break
            rows = carry
            carry = emptyList()
        } else {
            cursor = batch.last().timestamp
            val read = batch.asReversed().map { it.toChatMessage() }
            val variants = dao.getSelectedVariantsForMessages(chatId, read.filter { it.sender == "ai" }.map { it.timestamp })
                .associateBy { it.messageTimestamp }
            val shown = read.map { m -> variants[m.timestamp]?.applyTo(m, 0) ?: m }
            val all = shown + carry
            val start = all.indexOfFirst { it.sender == "user" }
            if (start < 0) { carry = all; continue }
            carry = all.subList(0, start)
            rows = all.subList(start, all.size)
        }
        val drawn = OrbitSpeech.history(rows, busy = newestTurn && !request.pageOpensTurn)
        kotlin.coroutines.coroutineContext.ensureActive()
        newestTurn = false
        for (message in drawn.asReversed()) {
            if (message.sender != "ai" || message.modelName.isBlank() || OrbitSpeech.hidden(message)) continue
            val role = request.shownRole(message.roleName.trim())
            if (role in wanted && role !in found) found[role] = ReplyModel(message.provider, message.modelName)
        }
        if (batch.isEmpty()) break
    }
    // Reaching the chat's start settles every role; stopping early leaves the rest unknown.
    return found to if (complete) emptySet() else wanted - found.keys
}

/** How many older rows an Orbit chat reads at most to find the replies before its page. */
private const val ORBIT_SEED_ROW_BUDGET = 400

/** How much text an Orbit chat reads at most to find the replies before its page. */
private const val ORBIT_SEED_BYTE_BUDGET = 4L * 1024 * 1024

private fun modelLabel(model: ReplyModel, withProvider: Boolean): String =
    if (withProvider && model.provider.isNotBlank()) "${model.provider.trim()} / ${model.modelName}" else model.modelName

/**
 * A quiet line above the first reply of the new model, like Codex's "Model changed"; drawn like
 * Orbit's time lines. The transcript's list already pads it from the screen edges.
 */
@Composable
internal fun ModelSwitchNotice(switch: ModelSwitch) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.chat_model_switched, switch.from, switch.to),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
