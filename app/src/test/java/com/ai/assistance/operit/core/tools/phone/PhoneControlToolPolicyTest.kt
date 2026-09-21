package com.ai.assistance.operit.core.tools.phone

import com.ai.assistance.operit.data.model.AITool
import org.junit.Assert.*
import org.junit.Test

class PhoneControlToolPolicyTest {
    @Test fun legacyBridgeWithoutCallerIdentityCannotBypassControl() {
        PhoneControlTools.retiredNames.forEach { name ->
            assertNotNull(name, PhoneControlTools.legacyBlockReason(AITool(name, emptyList())))
        }
        assertNull(PhoneControlTools.legacyBlockReason(AITool("phone_control:act", emptyList())))
        assertNull(PhoneControlTools.legacyBlockReason(AITool("list_installed_apps", emptyList())))
    }
}
