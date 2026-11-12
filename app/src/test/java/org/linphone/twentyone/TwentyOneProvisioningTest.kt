/*
 * fork: JVM testy rozdělení odkazu z QR na adresu konfigurace a klíč do
 * privátní sítě (část za #).
 */
package org.linphone.twentyone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TwentyOneProvisioningTest {
    private val key = "tskey-auth-kAbCdEf123456-XYZxyz0987654321"

    @Test
    fun keyAfterHashIsSplitOff() {
        val (url, k) = TwentyOneProvisioning.splitJoinKey(
            "http://100.64.0.1:8091/p/abcd1234#k=$key"
        )
        assertEquals("http://100.64.0.1:8091/p/abcd1234", url)
        assertEquals(key, k)
    }

    @Test
    fun urlWithoutHashIsUntouched() {
        val (url, k) = TwentyOneProvisioning.splitJoinKey("http://192.168.0.56:8091/p/abcd")
        assertEquals("http://192.168.0.56:8091/p/abcd", url)
        assertEquals("", k)
    }

    @Test
    fun otherFragmentParamsAreIgnored() {
        val (url, k) = TwentyOneProvisioning.splitJoinKey("http://h/p/x#v=2&k=$key")
        assertEquals("http://h/p/x", url)
        assertEquals(key, k)
    }

    @Test
    fun malformedKeyIsDropped() {
        assertEquals("", TwentyOneProvisioning.splitJoinKey("http://h/p/x#k=short").second)
        assertEquals("", TwentyOneProvisioning.splitJoinKey("http://h/p/x#k=bad key with spaces aaaaaaaa").second)
        assertEquals("", TwentyOneProvisioning.splitJoinKey("http://h/p/x#").second)
    }

    @Test
    fun redactHidesKey() {
        val shown = TwentyOneProvisioning.redact("linphone-config:http://h/p/x#k=$key")
        assertFalse(shown.contains(key))
        assertEquals("linphone-config:http://h/p/x#k=…", shown)
    }
}
