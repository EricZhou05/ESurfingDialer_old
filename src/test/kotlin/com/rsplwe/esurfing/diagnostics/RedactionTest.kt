package com.rsplwe.esurfing.diagnostics

import com.rsplwe.esurfing.cipher.KeyData
import com.rsplwe.esurfing.fixture.CanarySecrets
import com.rsplwe.esurfing.fixture.FixtureSecretScanner
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour contract of the [Redactor] redaction layer:
 *  - stable masking (never reversible, deterministic) for userName / phone /
 *    IP / MAC / schoolId
 *  - full removal (irreversible) for password / SMS / ticket / cookie / key
 *  - removal of exact known secret values wherever they appear
 *  - pure function: the input is NEVER mutated in place
 */
class RedactionTest {

    private val redactor: Redactor = Redactor.standard(
        extraRemoveValues = listOf(CanarySecrets.CANARY_PASSWORD_DEMO.toByteArray())
    )

    @Test
    fun masksPhoneIpAndMacRawValues() {
        val input = "phone=13800000000 ip=192.168.1.10 mac=AA:BB:CC:DD:EE:FF"
        val out = redactor.redactText(input)
        assertFalse(out.contains("13800000000"), "phone must be masked")
        assertFalse(out.contains("192.168.1.10"), "IP must be masked")
        assertFalse(out.contains("AA:BB:CC:DD:EE:FF"), "MAC must be masked")
        assertTrue(out.contains("[MASKED]"), "mask marker must be present")
    }

    @Test
    fun masksUsernameAndSchoolIdFields() {
        val input = """{"userName":"zhangsan","schoolId":"4400000001","userIp":"10.0.0.5","macAddress":"00:11:22:33:44:55"}"""
        val out = redactor.redactText(input)
        assertFalse(out.contains("zhangsan"), "userName must be masked")
        assertFalse(out.contains("4400000001"), "schoolId must be masked")
        assertFalse(out.contains("10.0.0.5"), "userIp must be masked")
        assertFalse(out.contains("00:11:22:33:44:55"), "macAddress must be masked")
        assertTrue(out.contains("[MASKED]"), "mask marker must be present")
    }

    @Test
    fun removesPasswordSmsTicketCookieAndKeyFields() {
        val input = """{"password":"s3cret","smsCode":"123456","ticket":"T-1","cookie":"sid=1","zsmKey":"K-1"}"""
        val out = redactor.redactText(input)
        for (secret in listOf("s3cret", "123456", "T-1", "sid=1", "K-1")) {
            assertFalse(out.contains(secret), "field value '$secret' must be fully removed")
        }
        assertTrue(out.contains("[REMOVED]"), "removal marker must be present")
    }

    @Test
    fun removesQueryStringAndXmlFieldValues() {
        val query = "https://example.invalid/login?userName=zhangsan&passwd=s3cret&ticket=T-1"
        val xml = """<Resp><userName>zhangsan</userName><passwd>s3cret</passwd><ticket>T-1</ticket></Resp>"""
        for (out in listOf(redactor.redactText(query), redactor.redactText(xml))) {
            assertFalse(out.contains("s3cret"), "password value must be removed from $out")
            assertFalse(out.contains("T-1"), "ticket value must be removed from $out")
            assertTrue(out.contains("[REMOVED]") || out.contains("[MASKED]"), "a marker must be present")
        }
    }

    @Test
    fun removesKnownSecretValuesWhereverTheyAppear() {
        val input = "plain log line with ${CanarySecrets.CANARY_PASSWORD_DEMO} embedded"
        val out = redactor.redactText(input)
        assertFalse(out.contains(CanarySecrets.CANARY_PASSWORD_DEMO), "known secret value must be removed")
        assertTrue(out.contains("[REMOVED]"), "removal marker must be present")
    }

    @Test
    fun standardRedactor_removesRealKeyDataBytes() {
        val realKey = KeyData.key1_CAFBCBAD_B6E7_4CAB_8A67_14D39F00CE1E
        val asLatin1 = String(realKey, Charsets.ISO_8859_1)
        val out = redactor.redactText("blob prefix " + asLatin1 + " blob suffix")
        assertFalse(out.contains(asLatin1), "real KeyData key bytes must be removed")
    }

    @Test
    fun redaction_isStableForSameInput() {
        val input = """{"userName":"zhangsan","password":"s3cret","ip":"10.0.0.5"}"""
        assertEquals(redactor.redactText(input), redactor.redactText(input), "same input must redact identically")
    }

    @Test
    fun redactionOutput_containsNoCanarySecrets() {
        val input = """{"userName":"zhangsan","password":"${CanarySecrets.CANARY_PASSWORD_DEMO}",""" +
            """"smsCode":"${CanarySecrets.CANARY_SMS_DEMO}","ticket":"${CanarySecrets.CANARY_TICKET_DEMO}",""" +
            """"key":"${CanarySecrets.CANARY_KEY_DEMO}"}"""
        val out = redactor.redactText(input)
        val hits = FixtureSecretScanner.scanText(out)
        assertTrue(hits.isEmpty(), "redacted output must not contain any canary secret, got: ${hits.joinToString()}")
    }

    @Test
    fun redactBytes_doesNotMutateInput() {
        val input = "user=zhangsan phone=13800000000".toByteArray()
        val copy = input.copyOf()
        redactor.redactBytes(input)
        assertContentEquals(copy, input, "redactBytes must never mutate the caller's array")
    }
}
