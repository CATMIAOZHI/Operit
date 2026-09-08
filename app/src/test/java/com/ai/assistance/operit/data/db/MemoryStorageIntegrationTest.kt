package com.ai.assistance.operit.data.db

import com.ai.assistance.operit.data.model.DocumentChunk
import com.ai.assistance.operit.data.model.Embedding
import com.ai.assistance.operit.data.model.Memory
import com.ai.assistance.operit.data.model.MemoryTag
import com.ai.assistance.operit.data.model.MyObjectBox
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the enhanced entities exported by memory-storage from the consuming app. */
class MemoryStorageIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `storage dependency preserves relations and converted values after reopening`() {
        val directory = temporaryFolder.newFolder("memory-storage")
        val vector = Embedding(floatArrayOf(0.25f, -0.5f, 1f))
        val memoryId = MyObjectBox.builder().directory(directory).build().use { store ->
            val tag = MemoryTag(name = "test-tag")
            store.boxFor(MemoryTag::class.java).put(tag)
            val memory = Memory(title = "module boundary", embedding = vector)
            memory.tags.add(tag)
            val id = store.boxFor(Memory::class.java).put(memory)
            val chunk = DocumentChunk(content = "test-chunk")
            chunk.memory.target = memory
            store.boxFor(DocumentChunk::class.java).put(chunk)
            id
        }

        MyObjectBox.builder().directory(directory).build().use { store ->
            val memory = store.boxFor(Memory::class.java).get(memoryId)
            assertEquals("module boundary", memory.title)
            assertEquals(vector, memory.embedding)
            assertEquals("test-tag", memory.tags.single().name)
            val chunk = memory.documentChunks.single()
            assertEquals("test-chunk", chunk.content)
            assertEquals(memoryId, chunk.memory.target.id)
        }
    }
}
