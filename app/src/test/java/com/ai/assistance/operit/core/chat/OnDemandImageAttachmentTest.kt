package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.AttachmentInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandImageAttachmentTest {
    @Test
    fun imageAttachmentContainsAddressAndReadInstructionWithoutImageLink() {
        val tag =
            AIMessageManager.buildOnDemandImageAttachmentTag(
                attachment =
                    AttachmentInfo(
                        filePath = "/sdcard/Download/attachment_1.png",
                        fileName = "diagram.png",
                        mimeType = "image/png",
                        fileSize = 42L,
                        content =
                            """existing note <link type="image" id="must-not-leak"></link>""",
                    ),
                readInstruction = "Call read_file when the image is needed.",
            )

        assertTrue(tag.contains("""id="/sdcard/Download/attachment_1.png""""))
        assertTrue(tag.contains("""filename="diagram.png""""))
        assertTrue(tag.contains("""type="image/png""""))
        assertTrue(tag.contains("Call read_file when the image is needed."))
        assertTrue(tag.contains("existing note"))
        assertFalse(tag.contains("""<link type="image""""))
        assertFalse(tag.contains("must-not-leak"))
    }
}
