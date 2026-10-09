package com.folio.batterysync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

class WirelessWireTest {
    @Test fun framesUnicodeAndRejectsInvalidLengths() {
        val buffer = ByteArrayOutputStream()
        WirelessWire.write(DataOutputStream(buffer), "手机电量")
        assertEquals("手机电量", WirelessWire.read(DataInputStream(ByteArrayInputStream(buffer.toByteArray()))))
        for (size in listOf(0, -1, 32_769)) {
            val invalid = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(size) }
            assertThrows(IllegalArgumentException::class.java) { WirelessWire.read(DataInputStream(ByteArrayInputStream(invalid.toByteArray()))) }
        }
        assertThrows(IllegalArgumentException::class.java) { WirelessWire.write(DataOutputStream(buffer), "x".repeat(32_769)) }
        assertThrows(EOFException::class.java) { WirelessWire.read(DataInputStream(ByteArrayInputStream(byteArrayOf(0, 0, 0, 10, 1)))) }
    }
    @Test fun rejectsCertificateWithDifferentFingerprint() {
        val certificate = "certificate".toByteArray()
        val correct = java.security.MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it) }
        assertTrue(WirelessWire.matchesFingerprint(correct, certificate))
        assertFalse(WirelessWire.matchesFingerprint("0".repeat(64), certificate))
        assertFalse(WirelessWire.matchesFingerprint(correct, "other".toByteArray()))
    }
}
