package com.ai.assistance.operit.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPoolRulesTest {
    @Test fun movesToTheNextAccountInOrderAndWrapsAround() {
        val ids = listOf("a", "b", "c")
        assertEquals("b", AccountPoolRules.nextUsable(ids, "a") { true })
        assertEquals("a", AccountPoolRules.nextUsable(ids, "c") { true })
    }

    @Test fun skipsAccountsThatCannotAnswer() {
        val ids = listOf("a", "b", "c")
        assertEquals("c", AccountPoolRules.nextUsable(ids, "a") { it != "b" })
    }

    @Test fun neverReturnsTheAccountItMovesOff() {
        assertNull(AccountPoolRules.nextUsable(listOf("a"), "a") { true })
        assertNull(AccountPoolRules.nextUsable(listOf("a", "b"), "a") { it == "a" })
    }

    @Test fun anUnknownActiveAccountStartsFromTheFirst() {
        assertEquals("a", AccountPoolRules.nextUsable(listOf("a", "b"), "gone") { true })
    }

    @Test fun aRefusedAccountRestsForTenMinutes() {
        var now = 0L
        val resting = RestingAccounts { now }
        resting.rest("a")
        assertTrue(resting.isResting("a"))
        assertFalse(resting.isResting("b"))
        now = AccountPoolRules.REST_MILLIS - 1
        assertTrue(resting.isResting("a"))
        now = AccountPoolRules.REST_MILLIS
        assertFalse(resting.isResting("a"))
    }

    @Test fun choosingAnAccountEndsItsRest() {
        val resting = RestingAccounts { 0L }
        resting.rest("a")
        resting.clear("a")
        assertFalse(resting.isResting("a"))
    }
}