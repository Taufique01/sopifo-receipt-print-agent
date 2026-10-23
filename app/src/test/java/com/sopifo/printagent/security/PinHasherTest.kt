package com.sopifo.printagent.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    @Test
    fun theRightPinMatchesAndAWrongOneDoesNot() {
        val stored = PinHasher.hash("4821")
        assertTrue(PinHasher.matches("4821", stored))
        assertFalse(PinHasher.matches("4822", stored))
        assertFalse(PinHasher.matches("", stored))
    }

    @Test
    fun thePinItselfIsNeverStoredAndEachHashIsSalted() {
        val a = PinHasher.hash("4821")
        val b = PinHasher.hash("4821")
        assertFalse(a.contains("4821"))
        assertNotEquals(a, b)
        assertTrue(PinHasher.matches("4821", b))
    }

    @Test
    fun onlyFourToEightDigitsAreAccepted() {
        assertTrue(PinHasher.isValid("1234"))
        assertTrue(PinHasher.isValid("12345678"))
        assertFalse(PinHasher.isValid("123"))
        assertFalse(PinHasher.isValid("123456789"))
        assertFalse(PinHasher.isValid("12a4"))
    }

    @Test
    fun aDamagedStoredValueNeverMatches() {
        assertFalse(PinHasher.matches("1234", "garbage"))
        assertFalse(PinHasher.matches("1234", "!!:??"))
    }
}
