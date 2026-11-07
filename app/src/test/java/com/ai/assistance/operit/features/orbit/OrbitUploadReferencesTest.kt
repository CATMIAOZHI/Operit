package com.ai.assistance.operit.features.orbit

import android.content.Context
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class OrbitUploadReferencesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(root: File): Context = mock<Context>().also {
        whenever(it.filesDir).thenReturn(root)
    }

    @Test fun persistedReferenceProtectsAnOldPendingAttachmentAfterRestart() {
        val root = temporary.newFolder()
        val upload = File(root, "orbit/test/uploads/1700000000000-abcd1234/photo.jpg")
        upload.parentFile!!.mkdirs()
        upload.writeText("photo")
        File(upload.parentFile, ".referenced").createNewFile()
        assertFalse(OrbitUploads.isReferenced(upload.path))

        OrbitUploads.discard(context(root), upload.path)

        assertEquals("photo", upload.readText())
    }

    @Test fun retainingAnUploadWritesADurableMarker() {
        val root = temporary.newFolder()
        val upload = File(root, "orbit/test/uploads/1700000000001-abcd1234/photo.jpg")
        upload.parentFile!!.mkdirs()
        upload.writeText("photo")

        OrbitUploads.retainReference(context(root), upload.path)

        assertTrue(File(upload.parentFile, ".referenced").isFile)
        assertTrue(OrbitUploads.isReferenced(upload.path))
    }

    @Test fun unreferencedPendingUploadsCanStillBeDiscarded() {
        val root = temporary.newFolder()
        val upload = File(root, "orbit/test/uploads/1700000000002-abcd1234/photo.jpg")
        upload.parentFile!!.mkdirs()
        upload.writeText("photo")

        OrbitUploads.discard(context(root), upload.path)

        assertFalse(upload.parentFile!!.exists())
    }
}
