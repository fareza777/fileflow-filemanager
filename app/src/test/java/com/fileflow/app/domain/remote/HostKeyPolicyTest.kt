package com.fileflow.app.domain.remote

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host key verification, tested on the decision table rather than on a server.
 *
 * The two cases that matter are the two that a file manager gets wrong: a
 * server it has never seen (must connect, and remember), and a server whose key
 * changed (must refuse, because that is what an intercepted connection looks
 * like). Both are decided here, with no JSch and no network.
 */
class HostKeyPolicyTest {

    private fun key(seed: Int): ByteArray =
        ByteArray(64) { index -> ((index * 31 + seed) % 251).toByte() }

    private fun entry(seed: Int, host: String = "example.com:22") = KnownHostKey(
        host = host,
        algorithm = "ssh-ed25519",
        key = Base64.getEncoder().encodeToString(key(seed)),
        fingerprint = HostKeyPolicy.fingerprint(key(seed)),
        addedAt = 1_700_000_000_000L,
    )

    @Test
    fun `a server never seen before is trusted on first use`() {
        assertEquals(HostKeyVerdict.UNKNOWN, HostKeyPolicy.decide(null, key(1)))
    }

    @Test
    fun `the same key again is a match`() {
        assertEquals(HostKeyVerdict.MATCH, HostKeyPolicy.decide(entry(1), key(1)))
    }

    @Test
    fun `a different key for the same host is a change, not a match`() {
        val verdict = HostKeyPolicy.decide(entry(1), key(2))
        assertEquals(HostKeyVerdict.CHANGED, verdict)
    }

    @Test
    fun `a one-bit difference is still a change`() {
        val stored = entry(1)
        val tampered = key(1).copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertEquals(HostKeyVerdict.CHANGED, HostKeyPolicy.decide(stored, tampered))
    }

    @Test
    fun `a key that cannot be decoded is treated as a change, not a match`() {
        // Failing closed matters more than failing politely: an unreadable
        // remembered key must never let a connection through.
        val broken = entry(1).copy(key = "not base64 !!!")
        assertEquals(HostKeyVerdict.CHANGED, HostKeyPolicy.decide(broken, key(1)))
    }

    @Test
    fun `the fingerprint is the OpenSSH shape`() {
        val fingerprint = HostKeyPolicy.fingerprint(key(1))
        assertTrue("expected SHA256:… but was $fingerprint", fingerprint.startsWith("SHA256:"))
        // 32 bytes of SHA-256 become 43 base64 characters, unpadded.
        assertEquals("SHA256:".length + 43, fingerprint.length)
        assertTrue("padding must not be part of it", !fingerprint.endsWith("="))
    }

    @Test
    fun `two different keys have two different fingerprints`() {
        assertNotEquals(HostKeyPolicy.fingerprint(key(1)), HostKeyPolicy.fingerprint(key(2)))
    }

    @Test
    fun `the host id keeps two ports on one machine apart`() {
        // The same box on two ports can be two different servers, and a key
        // remembered for one must not vouch for the other.
        assertNotEquals(
            HostKeyPolicy.hostId("example.com", 22),
            HostKeyPolicy.hostId("example.com", 2222),
        )
        assertEquals("example.com:22", HostKeyPolicy.hostId("example.com", 22))
    }
}
