package com.ai.assistance.operit.core.tools.defaultTool.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageReadRoutingTest {
    @Test
    fun attachmentImageFormatsAreHandledByTheOnDemandReader() {
        assertTrue(
            IMAGE_READ_EXTENSIONS.containsAll(
                listOf("jpg", "jpe", "jfif", "png", "webp", "heic")
            )
        )
    }

    @Test
    fun imageHeaderDetectionSupportsExtensionlessOrRenamedImages() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        val jpegHeader = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())

        assertTrue(hasRecognizedImageHeader(pngHeader))
        assertTrue(hasRecognizedImageHeader(jpegHeader))
    }

    @Test
    fun visionCapableParentUsesCurrentModel() {
        assertEquals(
            ImageReadRoute.CURRENT_MODEL,
            resolveImageReadRoute(
                hasRuntimeContext = true,
                parentModelSupportsVision = true,
                imageRecognitionModelAvailable = true,
                explicitDirectImage = false,
                hasIntent = true,
            ),
        )
    }

    @Test
    fun textOnlyParentUsesConfiguredImageRecognitionModelWithoutRequiringIntent() {
        assertEquals(
            ImageReadRoute.IMAGE_RECOGNITION_MODEL,
            resolveImageReadRoute(
                hasRuntimeContext = true,
                parentModelSupportsVision = false,
                imageRecognitionModelAvailable = true,
                explicitDirectImage = false,
                hasIntent = false,
            ),
        )
    }

    @Test
    fun textOnlyParentFallsBackToOcrWhenNoVisionRouteExists() {
        assertEquals(
            ImageReadRoute.OCR,
            resolveImageReadRoute(
                hasRuntimeContext = true,
                parentModelSupportsVision = false,
                imageRecognitionModelAvailable = false,
                explicitDirectImage = false,
                hasIntent = true,
            ),
        )
    }

    @Test
    fun legacyDirectImageRequestStillWorksOutsideConversationRuntime() {
        assertEquals(
            ImageReadRoute.CURRENT_MODEL,
            resolveImageReadRoute(
                hasRuntimeContext = false,
                parentModelSupportsVision = false,
                imageRecognitionModelAvailable = false,
                explicitDirectImage = true,
                hasIntent = false,
            ),
        )
    }
}
