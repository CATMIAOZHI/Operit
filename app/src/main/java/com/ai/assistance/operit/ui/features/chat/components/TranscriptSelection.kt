package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import com.ai.assistance.operit.data.model.ChatMessage
import kotlinx.coroutines.launch
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** UI callbacks still use display indices; the stored selection survives inserted history. */
internal class TranscriptSelection(
    /** Where many changes are folded into a new set, off the main thread; null: folded in place. */
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
) {
    /**
     * The picked messages, by time; rows ask this directly, so drawing never needs the indices.
     * Never changed in place: each change is a new set, safe to hand to background work, and
     * picking or unpicking one message costs about the same however many are picked.
     */
    var timestamps: Set<Long> by mutableStateOf<Set<Long>>(PickedTimes.EMPTY, referentialEqualityPolicy())
        private set

    fun replace(stamps: Set<Long>) {
        dropFold()
        timestamps = PickedTimes.of(stamps)
        if (stamps.isEmpty()) forget()
    }

    fun add(stamp: Long) {
        timestamps = current().plusOne(stamp)
        foldLater()
    }

    fun remove(stamp: Long) {
        timestamps = current().minusOne(stamp)
        if (timestamps.isEmpty()) forget() else foldLater()
    }

    private fun dropFold() {
        folding?.cancel()
        folding = null
        foldedFrom = null
        foldedTo = null
    }

    private var folding: kotlinx.coroutines.Job? = null
    // A folded copy of the selection made in the background, and the selection it copies. It is
    // only used for the next change: the selection shown keeps its object, so nothing comparing
    // or waiting on it sees a change that is not one.
    private var foldedFrom: PickedTimes? = null
    private var foldedTo: PickedTimes? = null

    /** What the next change starts from; a folded copy is used once and then let go. */
    private fun current(): PickedTimes {
        val folded = foldedTo?.takeIf { foldedFrom === timestamps }
        foldedFrom = null
        foldedTo = null
        return folded ?: PickedTimes.of(timestamps)
    }

    /** After many single changes, a plain copy is made in the background for the changes after. */
    private fun foldLater() {
        val now = timestamps as? PickedTimes ?: return
        if (!now.wantsFold || folding?.isActive == true) return
        val owner = scope ?: run { foldedFrom = now; foldedTo = now.folded(); return }
        folding = owner.launch {
            val folded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { now.folded() }
            // Kept only while it still copies the selection shown.
            if (timestamps === now) {
                foldedFrom = now
                foldedTo = folded
            }
        }
    }

    private var cachedMessages: List<ChatMessage>? = null
    private var cachedStamps: Set<Long>? = null
    private var cachedIndices: Set<Int> = emptySet()

    private fun forget() {
        dropFold()
        cachedMessages = null
        cachedStamps = null
        cachedIndices = emptySet()
    }

    fun forMessages(messages: List<ChatMessage>): ReadWriteProperty<Any?, Set<Int>> =
        object : ReadWriteProperty<Any?, Set<Int>> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): Set<Int> {
                val stamps = timestamps
                if (stamps.isEmpty()) {
                    forget()
                    return emptySet()
                }
                // Read many times per frame while selecting; worked out again only when the
                // messages or the selection change, not on every read.
                if (messages === cachedMessages && stamps === cachedStamps) return cachedIndices
                return indicesOf(messages, stamps).also {
                    cachedMessages = messages
                    cachedStamps = stamps
                    cachedIndices = it
                }
            }

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Set<Int>) {
                replace(value.mapNotNullTo(linkedSetOf()) { messages.getOrNull(it)?.timestamp })
            }
        }

    companion object {
        /**
         * Where [stamps] are in [messages], in order. Messages run oldest first, so each is found by a
         * binary search (as many steps as the selection, not the chat, is long); a message out of order
         * or gone sends it to one plain pass.
         */
        fun indicesOf(messages: List<ChatMessage>, stamps: Set<Long>): Set<Int> =
            indicesOrNull(messages, stamps) ?: messages.indices.filterTo(linkedSetOf()) { messages[it].timestamp in stamps }

        /** [indicesOf] by binary search alone; null when a message is not found that way. */
        fun indicesOrNull(messages: List<ChatMessage>, stamps: Set<Long>): Set<Int>? {
            val found = ArrayList<Int>(stamps.size)
            for (stamp in stamps) {
                val at = find(messages, stamp)
                if (at < 0) return null
                // Messages sharing the time are all picked, as before.
                var first = at
                while (first > 0 && messages[first - 1].timestamp == stamp) first--
                var i = first
                while (i < messages.size && messages[i].timestamp == stamp) found += i++
            }
            found.sort()
            return found.toCollection(LinkedHashSet(found.size * 2))
        }

        /** Where a message of time [stamp] is in [messages] by binary search, or -1. */
        fun find(messages: List<ChatMessage>, stamp: Long): Int {
            var low = 0
            var high = messages.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val t = messages[mid].timestamp
                when {
                    t < stamp -> low = mid + 1
                    t > stamp -> high = mid - 1
                    else -> return mid
                }
            }
            return -1
        }
    }
}

/**
 * A set of message times that is never changed in place. A change keeps the big [base] and notes
 * the few times put in ([added]) or taken out ([removed]); after many changes they are folded into a
 * new base. So one change costs little even when thousands are picked.
 */
internal class PickedTimes private constructor(
    private val base: Set<Long>,
    private val added: Set<Long>,
    private val removed: Set<Long>,
    override val size: Int,
) : AbstractSet<Long>() {
    override fun contains(element: Long): Boolean = element in added || (element in base && element !in removed)

    override fun iterator(): Iterator<Long> =
        (base.asSequence().filter { it !in removed } + added.asSequence()).iterator()

    fun plusOne(stamp: Long): PickedTimes {
        if (stamp in this) return this
        return if (stamp in base) next(base, added, removed - stamp, size + 1)
        else next(base, added + stamp, removed, size + 1)
    }

    fun minusOne(stamp: Long): PickedTimes {
        if (stamp !in this) return this
        return if (stamp in base) next(base, added, removed + stamp, size - 1)
        else next(base, added - stamp, removed, size - 1)
    }

    private fun next(base: Set<Long>, added: Set<Long>, removed: Set<Long>, size: Int): PickedTimes =
        PickedTimes(base, added, removed, size)

    /** Many changes noted: worth folding into one set (see [folded]). */
    val wantsFold: Boolean get() = added.size + removed.size > FOLD_AFTER

    /** The same times as one plain set; as long as the selection, so made off the main thread. */
    fun folded(): PickedTimes = PickedTimes(HashSet(this), emptySet(), emptySet(), size)

    companion object {
        private const val FOLD_AFTER = 64
        val EMPTY = PickedTimes(emptySet(), emptySet(), emptySet(), 0)

        fun of(stamps: Set<Long>): PickedTimes =
            stamps as? PickedTimes ?: if (stamps.isEmpty()) EMPTY else PickedTimes(HashSet(stamps), emptySet(), emptySet(), stamps.size)
    }
}
