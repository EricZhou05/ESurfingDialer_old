package com.rsplwe.esurfing

import com.rsplwe.esurfing.fixture.CanarySecrets
import com.rsplwe.esurfing.fixture.FixtureLoader
import com.rsplwe.esurfing.fixture.FixtureSecretScanner
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Secret scanner contract:
 *  1. The scanner MUST detect an injected canary (proving it actually works).
 *  2. Scanning the committed fixtures MUST return zero hits (no real key/IV,
 *     no canary sentinel, no live field data).
 */
class FixtureSecretScannerTest {

    @Test
    fun scanner_detectsInjectedCanaryPassword() {
        val hits = FixtureSecretScanner.scanText(
            "some output ... " + CanarySecrets.CANARY_PASSWORD_DEMO + " ... end"
        )
        assertTrue(hits.isNotEmpty(), "scanner must detect an injected canary password")
    }

    @Test
    fun scanner_detectsInjectedCanaryTicketAndKey() {
        val hits = FixtureSecretScanner.scanText(
            "log: " + CanarySecrets.CANARY_TICKET_DEMO + " " + CanarySecrets.CANARY_KEY_DEMO
        )
        assertTrue(hits.isNotEmpty(), "scanner must detect injected canary ticket/key")
    }

    @Test
    fun scanner_detectsInjectedCanaryInByteArray() {
        val payload = "header\n" + CanarySecrets.CANARY_BYTE_KEY.decodeToString() + "\nfooter"
        val hits = FixtureSecretScanner.scanText(payload)
        assertTrue(hits.isNotEmpty(), "scanner must detect injected canary byte key")
    }

    @Test
    fun scanner_detectsRealKeyDataBytes() {
        // A fake "fixture" that smuggles a real production key must be rejected.
        val fake = "FICTIONAL_BLOB_" + com.rsplwe.esurfing.cipher.KeyData
            .key1_CAFBCBAD_B6E7_4CAB_8A67_14D39F00CE1E
            .joinToString("") { "%02x".format(it) }
        val hits = FixtureSecretScanner.scanText(fake)
        assertTrue(hits.isNotEmpty(), "scanner must reject samples containing real KeyData keys")
    }

    @Test
    fun committedFixtures_areClean() {
        val hits = FixtureSecretScanner.scanAllFixtures()
        assertTrue(
            hits.isEmpty(),
            "committed fixtures must contain no real key/IV, no canary and no live data, but found: ${hits.joinToString()}"
        )
    }

    @Test
    fun fixtureFiles_doNotEmbedCanarySentinel() {
        for (file in FixtureLoader.allFixtureFiles()) {
            val text = file.readText()
            assertTrue(
                !CanarySecrets.all.any { text.contains(it) },
                "${file.name} must not contain any canary sentinel"
            )
        }
    }
}
