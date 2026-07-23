package com.ai.assistance.operit.data.preferences

import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.FavoriteModelRef
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.model.getModelByIndex
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.model.getValidModelIndex
import com.ai.assistance.operit.data.model.isProviderCollapsed
import com.ai.assistance.operit.data.model.mergeCollapsedConfigIds
import com.ai.assistance.operit.data.model.normalizeConfigOrder
import com.ai.assistance.operit.data.model.normalizeProviderId
import com.ai.assistance.operit.data.model.partitionConfigsByCollapsedIds
import com.ai.assistance.operit.data.model.resolveFavoriteModelIndex
import com.ai.assistance.operit.data.model.resolveValidFavorites
import org.junit.Assert.*
import org.junit.Test

class ModelListPreferencesTest {

    // ==================== normalizeProviderId ====================

    @Test
    fun normalizeProviderId_trimAndLowercase() {
        assertEquals("deepseek", normalizeProviderId("  DeepSeek  "))
        assertEquals("ollama", normalizeProviderId("OLLAMA"))
        assertEquals("", normalizeProviderId("   "))
    }

    // ==================== isProviderCollapsed ====================

    @Test
    fun isProviderCollapsed_trueWhenNormalizedMatch() {
        val collapsed = setOf("deepseek", "ollama")
        assertTrue(isProviderCollapsed("  DeepSeek  ", collapsed))
        assertTrue(isProviderCollapsed("ollama", collapsed))
        assertFalse(isProviderCollapsed("openai", collapsed))
        assertFalse(isProviderCollapsed("", collapsed))
    }

    // ==================== normalizeConfigOrder ====================

    @Test
    fun normalizeConfigOrder_moveLastToFirst() {
        val result = normalizeConfigOrder(listOf("c", "a", "b"), listOf("a", "b", "c"))
        assertEquals(listOf("c", "a", "b"), result)
    }

    @Test
    fun normalizeConfigOrder_defaultCanMove() {
        val result = normalizeConfigOrder(listOf("b", "default", "a"), listOf("default", "a", "b"))
        assertEquals(listOf("b", "default", "a"), result)
    }

    @Test
    fun normalizeConfigOrder_deduplicateRequestIds() {
        val result = normalizeConfigOrder(listOf("a", "b", "a", "c"), listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun normalizeConfigOrder_ignoreDeletedIds() {
        val result = normalizeConfigOrder(listOf("a", "deleted", "b"), listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun normalizeConfigOrder_missingCurrentIdsAppended() {
        val result = normalizeConfigOrder(listOf("b"), listOf("a", "b", "c", "d"))
        assertEquals(listOf("b", "a", "c", "d"), result)
    }

    @Test
    fun normalizeConfigOrder_emptyRequestPreservesAll() {
        val result = normalizeConfigOrder(emptyList(), listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun normalizeConfigOrder_unchangedRequestKeepsOrder() {
        val result = normalizeConfigOrder(listOf("a", "b", "c"), listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), result)
    }

    // ==================== resolveFavoriteModelIndex ====================

    @Test
    fun resolveFavoriteModelIndex_findsCorrectIndex() {
        val summaries = listOf(
            mkSummary("c1", modelName = "gpt-4,claude-3,gemini")
        )
        val fav = FavoriteModelRef("c1", "claude-3")
        assertEquals(1, resolveFavoriteModelIndex(summaries, fav))
    }

    @Test
    fun resolveFavoriteModelIndex_notFoundWhenConfigMissing() {
        val summaries = listOf(mkSummary("c1", modelName = "gpt-4"))
        val fav = FavoriteModelRef("c2", "gpt-4")
        assertNull(resolveFavoriteModelIndex(summaries, fav))
    }

    @Test
    fun resolveFavoriteModelIndex_notFoundWhenModelRemoved() {
        val summaries = listOf(mkSummary("c1", modelName = "gpt-4"))
        val fav = FavoriteModelRef("c1", "removed-model")
        assertNull(resolveFavoriteModelIndex(summaries, fav))
    }

    @Test
    fun resolveFavoriteModelIndex_differentConfigsNotConfused() {
        val summaries = listOf(
            mkSummary("c1", "d1", modelName = "gpt-4"),
            mkSummary("c2", "d2", modelName = "gpt-4"),
        )
        val fav = FavoriteModelRef("c2", "gpt-4")
        val idx = resolveFavoriteModelIndex(summaries, fav)
        assertNotNull(idx)
        assertEquals(0, idx)
        // Verify it's from c2, not c1
        assertEquals("d2", summaries.find { it.id == "c2" }?.apiProviderTypeId)
    }

    @Test
    fun resolveFavoriteModelIndex_modelListReorderedStillFindsByName() {
        val summaries = listOf(
            mkSummary("c1", modelName = "gemini,gpt-4,claude-3")
        )
        val fav = FavoriteModelRef("c1", "gpt-4")
        assertEquals(1, resolveFavoriteModelIndex(summaries, fav))
    }

    // ==================== resolveValidFavorites ====================

    @Test
    fun resolveValidFavorites_filtersInvalidAndDeduplicates() {
        val summaries = listOf(
            mkSummary("c1", modelName = "gpt-4"),
            mkSummary("c2", modelName = "claude-3"),
        )
        val favs = listOf(
            FavoriteModelRef("c1", "gpt-4"),       // valid
            FavoriteModelRef("c1", "gpt-4"),       // duplicate -> removed
            FavoriteModelRef("c2", "missing"),      // invalid
            FavoriteModelRef("c3", "gpt-4"),       // config missing
            FavoriteModelRef("c2", "claude-3"),     // valid
        )
        val result = resolveValidFavorites(summaries, favs)
        assertEquals(2, result.size)
        assertEquals("c1" to "gpt-4", result[0].configId to result[0].modelName)
        assertEquals("c2" to "claude-3", result[1].configId to result[1].modelName)
    }

    // ==================== partitionConfigsByCollapsedIds ====================

    @Test
    fun partitionConfigsByCollapsedIds_singleConfigCollapsed_sameProviderOthersNormal() {
        val summaries = listOf(
            mkSummary("c1", "deepseek", name = "DeepSeek 1"),
            mkSummary("c2", "ollama", name = "Ollama Local"),
            mkSummary("c3", "deepseek", name = "DeepSeek 2"),
            mkSummary("c4", "openai", name = "OpenAI"),
        )
        val collapsed = setOf("c1")
        val (normal, collapsedList) = partitionConfigsByCollapsedIds(summaries, collapsed)
        assertEquals(listOf("Ollama Local", "DeepSeek 2", "OpenAI"), normal.map { it.name })
        assertEquals(listOf("DeepSeek 1"), collapsedList.map { it.name })
    }

    @Test
    fun partitionConfigsByCollapsedIds_multiProviderIndependentCollapse() {
        val summaries = listOf(
            mkSummary("c1", "deepseek", name = "DeepSeek"),
            mkSummary("c2", "ollama", name = "Ollama"),
            mkSummary("c3", "openai", name = "OpenAI"),
        )
        val collapsed = setOf("c1", "c3")
        val (normal, collapsedList) = partitionConfigsByCollapsedIds(summaries, collapsed)
        assertEquals(listOf("Ollama"), normal.map { it.name })
        assertEquals(listOf("DeepSeek", "OpenAI"), collapsedList.map { it.name })
    }

    @Test
    fun partitionConfigsByCollapsedIds_preservesGlobalOrder() {
        val summaries = listOf(
            mkSummary("a", "p1", name = "A"),
            mkSummary("b", "p2", name = "B"),
            mkSummary("c", "p1", name = "C"),
            mkSummary("d", "p3", name = "D"),
        )
        val collapsed = setOf("b", "d")
        val (normal, collapsedList) = partitionConfigsByCollapsedIds(summaries, collapsed)
        assertEquals(listOf("A", "C"), normal.map { it.name })
        assertEquals(listOf("B", "D"), collapsedList.map { it.name })
    }

    @Test
    fun partitionConfigsByCollapsedIds_emptySetAllNormal() {
        val summaries = listOf(
            mkSummary("c1", name = "A"),
            mkSummary("c2", name = "B"),
        )
        val (normal, collapsedList) = partitionConfigsByCollapsedIds(summaries, emptySet())
        assertEquals(listOf("A", "B"), normal.map { it.name })
        assertTrue(collapsedList.isEmpty())
    }

    @Test
    fun partitionConfigsByCollapsedIds_nonExistentIdIgnored() {
        val summaries = listOf(mkSummary("c1", name = "A"))
        val (normal, collapsedList) = partitionConfigsByCollapsedIds(summaries, setOf("ghost"))
        assertEquals(listOf("A"), normal.map { it.name })
        assertTrue(collapsedList.isEmpty())
    }

    // ==================== mergeCollapsedConfigIds ====================

    @Test
    fun mergeCollapsedConfigIds_unionOfLocalAndBackup() {
        val result = mergeCollapsedConfigIds(
            localIds = setOf("a", "b"),
            backupIds = listOf("b", "c"),
            mergedConfigIds = listOf("a", "b", "c", "d"),
        )
        assertEquals(setOf("a", "b", "c"), result)
    }

    @Test
    fun mergeCollapsedConfigIds_filtersOutGhostIds() {
        val result = mergeCollapsedConfigIds(
            localIds = setOf("a", "ghost"),
            backupIds = listOf("c"),
            mergedConfigIds = listOf("a", "c", "d"),
        )
        assertEquals(setOf("a", "c"), result)
    }

    @Test
    fun mergeCollapsedConfigIds_v1EmptyBackupPreservesLocal() {
        // v1 scenario: backup has no collapsedConfigIds, only local state
        val result = mergeCollapsedConfigIds(
            localIds = setOf("a", "b"),
            backupIds = emptyList(),
            mergedConfigIds = listOf("a", "b", "c"),
        )
        assertEquals(setOf("a", "b"), result)
    }

    @Test
    fun mergeCollapsedConfigIds_v2MergesUnion() {
        val result = mergeCollapsedConfigIds(
            localIds = setOf("x"),
            backupIds = listOf("y", "z"),
            mergedConfigIds = listOf("x", "y", "z"),
        )
        assertEquals(setOf("x", "y", "z"), result)
    }

    // ==================== getModelByIndex / getModelList ====================

    @Test
    fun getModelList_splitsAndTrimsCommas() {
        val result = getModelList(" gpt-4 , claude-3,  gemini ")
        assertEquals(listOf("gpt-4", "claude-3", "gemini"), result)
    }

    @Test
    fun getModelList_emptyStringReturnsEmpty() {
        assertTrue(getModelList("").isEmpty())
        assertTrue(getModelList("  , , ").isEmpty())
    }

    @Test
    fun getValidModelIndex_clampsToZeroOnOverflow() {
        assertEquals(0, getValidModelIndex("model1,model2", 5))
        assertEquals(0, getValidModelIndex("model1,model2", -1))
    }

    // ==================== helpers ====================

    private fun mkSummary(
        id: String,
        providerTypeId: String = "deepseek",
        name: String = "Config $id",
        modelName: String = "default-model",
    ): ModelConfigSummary {
        val providerType = ApiProviderType.fromProviderTypeId(providerTypeId) ?: ApiProviderType.OTHER
        return ModelConfigSummary(
            id = id,
            name = name,
            modelName = modelName,
            apiProviderType = providerType,
            apiProviderTypeId = providerTypeId,
        )
    }
}
