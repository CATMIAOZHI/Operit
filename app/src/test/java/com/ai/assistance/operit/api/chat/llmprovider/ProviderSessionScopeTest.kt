package com.ai.assistance.operit.api.chat.llmprovider

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A review that runs in a reviewer chat of its own still has to ask its provider under the same
 * conversation identity as the reviews before it, because that identity is what decides whether the
 * cached prompt prefix is reused. These pin the two properties that make the derived identity work:
 * it never changes for one scope, and two scopes never share it.
 */
class ProviderSessionScopeTest {
    @Test
    fun oneScopeAlwaysResolvesToTheSameIdentity() {
        val scope = "permission_reviewer:0a6f2a2a-1111-2222-3333-444455556666"

        assertEquals(providerSessionIdForScope(scope), providerSessionIdForScope(scope))
    }

    @Test
    fun differentConversationsDoNotShareAnIdentity() {
        assertNotEquals(
            providerSessionIdForScope("permission_reviewer:chat-one"),
            providerSessionIdForScope("permission_reviewer:chat-two"),
        )
    }

    @Test
    fun differentReadersOfOneConversationDoNotShareAnIdentity() {
        assertNotEquals(
            providerSessionIdForScope("permission_reviewer:chat-one"),
            providerSessionIdForScope("permission_risk_scorer:chat-one"),
        )
    }

    /** The value is handed to providers that have only ever seen conversation identities this shape. */
    @Test
    fun theIdentityHasTheShapeEveryOtherIdentityHas() {
        val identity = providerSessionIdForScope("permission_reviewer:chat-one")

        assertEquals(identity, UUID.fromString(identity).toString())
    }
}
