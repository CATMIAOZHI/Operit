package com.ai.assistance.operit.core.tools.phone

import com.ai.assistance.operit.core.tools.SimplifiedUINode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

internal data class PhoneControlElement(
    val index: Int, val node: SimplifiedUINode,
    val left: Int, val top: Int, val right: Int, val bottom: Int
) {
    val x: Int get() = left + (right - left) / 2
    val y: Int get() = top + (bottom - top) / 2
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
    fun sameTarget(other: PhoneControlElement) =
        node.resourceId == other.node.resourceId && node.className == other.node.className &&
            node.text == other.node.text && node.contentDesc == other.node.contentDesc &&
            left == other.left && top == other.top && right == other.right && bottom == other.bottom &&
            node.isClickable == other.node.isClickable
}

internal object PhoneControlBatch {
    const val MAX_ACTIONS = 20
    private val bounds = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")

    fun elements(root: SimplifiedUINode): List<PhoneControlElement> {
        val result = mutableListOf<PhoneControlElement>()
        fun visit(node: SimplifiedUINode) {
            val rect = bounds.matchEntire(node.bounds.orEmpty())?.groupValues?.drop(1)?.map { it.toIntOrNull() }
            if (rect != null && rect.all { it != null } &&
                (!node.text.isNullOrBlank() || !node.contentDesc.isNullOrBlank() || node.isClickable)) {
                val (left, top, right, bottom) = rect.map { requireNotNull(it) }
                if (right > left && bottom > top) {
                    result.add(PhoneControlElement(result.size + 1, node, left, top, right, bottom))
                }
            }
            node.children.forEach(::visit)
        }
        visit(root)
        return result
    }

    fun actions(args: Map<String, String>): List<Map<String, String>> {
        val batch = args["actions"] ?: return listOf(args)
        require(args["action"] == null) { "Use either action or actions, not both." }
        val array = JSONArray(batch)
        require(array.length() in 1..MAX_ACTIONS) { "actions must contain 1 to $MAX_ACTIONS actions." }
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            row.keys().asSequence().associateWith { key ->
                val value = row.get(key)
                require(value is String || value is Number) { "Invalid action field: $key" }
                value.toString()
            }
        }
    }

    fun target(elements: List<PhoneControlElement>, x: Int, y: Int): PhoneControlElement? =
        elements.filter { it.node.isClickable && it.contains(x, y) }
            .minByOrNull { (it.right - it.left).toLong() * (it.bottom - it.top) }

    fun verifyTarget(target: PhoneControlElement, current: List<PhoneControlElement>) {
        check(current.count { target.sameTarget(it) } == 1) {
            "Target control changed or is ambiguous. Remaining actions were not sent; observe again."
        }
    }

    fun resolveTarget(target: PhoneControlElement, current: List<PhoneControlElement>): PhoneControlElement {
        if (target.node.resourceId.isNullOrBlank()) {
            verifyTarget(target, current)
            return current.single { target.sameTarget(it) }
        }
        // Resource IDs may repeat in lists: retain semantic identity and require uniqueness.
        return checkNotNull(current.singleOrNull {
            it.node.resourceId == target.node.resourceId && it.node.className == target.node.className &&
                it.node.text == target.node.text && it.node.contentDesc == target.node.contentDesc &&
                it.node.isClickable == target.node.isClickable
        }) { "Target control changed or is ambiguous. Remaining actions were not sent." }
    }
}

internal data class PhoneBatchProgress(
    val completed: Int, val attempted: Int, val total: Int, val error: String? = null
)

/** Keep partial progress; neither validation failures nor uncertain input failures are retried. */
internal suspend fun runPhoneBatch(
    total: Int,
    before: suspend (Int) -> Unit,
    perform: suspend (Int) -> String?,
    timeoutMs: Long = 45_000
): PhoneBatchProgress {
    var completed = 0
    var attempted = 0
    return withTimeoutOrNull(timeoutMs) {
        try {
            repeat(total) { index ->
                currentCoroutineContext().ensureActive()
                before(index)
                currentCoroutineContext().ensureActive()
                attempted++
                val failure = perform(index)
                currentCoroutineContext().ensureActive()
                if (failure != null) return@withTimeoutOrNull PhoneBatchProgress(completed, attempted, total, failure)
                completed++
            }
            PhoneBatchProgress(completed, attempted, total)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PhoneBatchProgress(completed, attempted, total, error.message ?: error.javaClass.simpleName)
        }
    } ?: PhoneBatchProgress(completed, attempted, total, "Batch timed out. An attempted input may have taken effect; observe before retrying.")
}
