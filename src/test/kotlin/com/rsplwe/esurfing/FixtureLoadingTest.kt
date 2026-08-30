package com.rsplwe.esurfing

import com.google.gson.JsonParser
import com.rsplwe.esurfing.cipher.KeyData
import com.rsplwe.esurfing.fixture.CanarySecrets
import com.rsplwe.esurfing.fixture.FixtureLoader
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Green baseline: every committed fixture loads from the classpath, its fields
 * are deterministic, and the canary constants are addressable and distinct from
 * every real key/IV in production KeyData.
 */
class FixtureLoadingTest {

    @Test
    fun portalHtml_loadsAndContainsFictionalMarker() {
        val html = FixtureLoader.text("portal/portal-login.html")
        assertTrue(html.contains("config.campus.js.chinatelecom.com"), "portal start marker")
        assertTrue(html.contains("FICTIONAL"), "fixture must be clearly fictitious")
        assertFalse(html.contains("CANARY_"), "fixtures must not embed canary sentinels")
    }

    @Test
    fun portalConfigJson_parsesAndFieldsAreDeterministic() {
        val json = JsonParser.parseString(FixtureLoader.text("json/portal-config.json")).asJsonObject
        val network = json.getAsJsonObject("network")
        assertEquals("192.0.2.123", network.get("userIp").asString)
        assertEquals("192.0.2.1", network.get("acIp").asString)
        assertEquals("AA:BB:CC:DD:EE:FF", network.get("mac").asString)
        val endpoints = json.getAsJsonObject("endpoints")
        assertEquals("http://fixture.example.invalid/portal/ticket", endpoints.get("ticketUrl").asString)
        assertEquals(30, json.getAsJsonObject("expectedFields").get("keepRetry").asInt)
    }

    @Test
    fun loginResponseXml_parsesRequiredFields() {
        val doc = Jsoup.parse(FixtureLoader.text("xml/login-response.xml"), Parser.xmlParser())
        assertEquals("success", doc.getElementsByTag("result").first()?.text())
        assertEquals("http://fixture.example.invalid/portal/keep", doc.getElementsByTag("keep-url").first()?.text())
        assertEquals("http://fixture.example.invalid/portal/term", doc.getElementsByTag("term-url").first()?.text())
        assertEquals("30", doc.getElementsByTag("keep-retry").first()?.text())
    }

    @Test
    fun ticketResponseXml_parsesTicketField() {
        val doc = Jsoup.parse(FixtureLoader.text("xml/ticket-response.xml"), Parser.xmlParser())
        assertEquals("FIXTURE_TICKET_0000000000000000", doc.getElementsByTag("ticket").first()?.text())
    }

    @Test
    fun zsmValid_isDeterministicAndMatchesLayout() {
        val zsm = FixtureLoader.bytes("zsm/valid.zsm")
        assertEquals(57, zsm.size)
        assertEquals("AAA", zsm.sliceArray(0 until 3).decodeToString())
        val keyLen = zsm[3].toInt()
        assertEquals(16, keyLen)
        // key slice
        val key = zsm.sliceArray(4 until 4 + keyLen).decodeToString()
        assertEquals("CAnary-K3y-AB12X", key)
        // algo id slice
        val algoLen = zsm[4 + keyLen].toInt()
        assertEquals(36, algoLen)
        val algo = zsm.sliceArray(5 + keyLen until 5 + keyLen + algoLen).decodeToString()
        assertEquals("CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E", algo)
    }

    @Test
    fun cipherVectors_jsonIsDeterministic() {
        val json = JsonParser.parseString(FixtureLoader.text("cipher/cipher-vectors.json")).asJsonObject
        assertTrue(json.get("fictitious").asBoolean)
        val vectors = json.getAsJsonArray("vectors")
        assertEquals(2, vectors.size())
        val first = vectors[0].asJsonObject
        assertEquals("CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E", first.get("algoId").asString)
        assertEquals("101112131415161718191a1b1c1d1e1f", first.get("key1Hex").asString)
        assertEquals("46494354494f4e414c2d504c41494e54", first.get("plaintextHex").asString)
    }

    @Test
    fun allFixtures_areNonEmpty() {
        val files = FixtureLoader.allFixtureFiles()
        assertTrue(files.size >= 9, "expected at least 9 fixture files, got ${files.size}")
        for (file in files) {
            assertTrue(file.length() > 0, "fixture ${file.name} must not be empty")
        }
    }

    @Test
    fun canaryConstants_areAddressableAndFictitious() {
        assertTrue(CanarySecrets.CANARY_PASSWORD_DEMO.startsWith("CAnary-"))
        assertTrue(CanarySecrets.CANARY_TICKET_DEMO.startsWith("CAnary-"))
        assertTrue(CanarySecrets.CANARY_KEY_DEMO.startsWith("CAnary-"))
        assertTrue(CanarySecrets.CANARY_SMS_DEMO.startsWith("CAnary-"))
        assertEquals(16, CanarySecrets.CANARY_BYTE_KEY.size)
    }

    @Test
    fun canaryConstants_differFromEveryRealKeyDataEntry() {
        // Serialize every production key/IV (ByteArray and IntArray) and assert
        // no canary string equals any of them (hex or raw).
        val realKeyHex = mutableListOf<String>()
        for (field in KeyData::class.java.declaredFields) {
            field.isAccessible = true
            when (val value = field.get(null)) {
                is ByteArray -> realKeyHex.add(value.joinToString("") { "%02x".format(it) })
                is IntArray -> realKeyHex.add(value.joinToString("") { "%08x".format(it) })
            }
        }
        assertTrue(realKeyHex.isNotEmpty(), "production KeyData must expose real keys")
        for (canary in CanarySecrets.all) {
            assertNotNull(canary)
            assertTrue(
                realKeyHex.none { it == canary || it.contains(canary) },
                "canary '$canary' must never collide with a real key/IV"
            )
        }
    }
}
