package com.rsplwe.esurfing.diagnostics

import com.rsplwe.esurfing.cipher.KeyData
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets

/** Whether a [RedactionRule] masks the matched value or removes it entirely. */
enum class RedactionAction { MASK, REMOVE }

/**
 * A named regex rule. [replacement] receives the [MatchResult] and returns the
 * substituted text; it is how stable masking keeps field names while replacing
 * only the value.
 */
data class RedactionRule(
    val name: String,
    val pattern: Regex,
    val action: RedactionAction,
    val replacement: (MatchResult) -> String,
)

/** Boundary seam for the evidence layer; tests inject a failing fake here. */
interface EvidenceRedactor {
    fun redactText(text: String): String
    fun redactBytes(input: ByteArray): ByteArray
}

/**
 * Deterministic, irreversible redaction layer.
 *
 *  - **MASK** (stable, never reversible): userName / username / loginUser /
 *    userId / schoolId / userIp / acIp / ip / mac / macAddress plus raw phone
 *    numbers, IPv4 addresses and MAC addresses → replaced with `[MASKED]`.
 *  - **REMOVE** (fully removed, irrecoverable): password / passwd / pwd / sms /
 *    smsCode / verifyCode / authCode / ticket / cookie / zsmKey / key / iv /
 *    token / secret in JSON, query-string, XML and header forms → `[REMOVED]`.
 *  - **Known secret values** (exact byte sequences) are removed wherever they
 *    appear — including the real static keys/IVs from production [KeyData], so
 *    binary blobs embedding a key lose it at the byte level.
 *
 * The input array is never mutated in place: [redactBytes] works on a copy and
 * [redactText] is a pure function. Redaction is deterministic (same input →
 * same output) and irreversible (only fixed markers remain).
 */
class Redactor private constructor(
    private val maskRules: List<RedactionRule>,
    private val removeRules: List<RedactionRule>,
    private val removeValues: List<ByteArray>,
) : EvidenceRedactor {

    override fun redactText(text: String): String =
        String(redactBytes(text.toByteArray(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)

    override fun redactBytes(input: ByteArray): ByteArray {
        // 1. Byte-level removal of exact known secrets — works on binary blobs.
        var out = input.copyOf()
        for (secret in removeValues) {
            out = removeBytes(out, secret)
        }
        // 2. Decode leniently (malformed bytes become U+FFFD) and apply rules.
        var text = String(out, StandardCharsets.UTF_8)
        for (rule in removeRules) {
            text = rule.pattern.replace(text) { rule.replacement(it) }
        }
        for (rule in maskRules) {
            text = rule.pattern.replace(text) { rule.replacement(it) }
        }
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private fun removeBytes(haystack: ByteArray, needle: ByteArray): ByteArray {
        if (needle.isEmpty()) return haystack
        val marker = REMOVED.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream(haystack.size)
        var i = 0
        while (i <= haystack.size - needle.size) {
            if (matchesAt(haystack, needle, i)) {
                out.write(marker)
                i += needle.size
            } else {
                out.write(haystack[i].toInt())
                i++
            }
        }
        while (i < haystack.size) {
            out.write(haystack[i].toInt())
            i++
        }
        return out.toByteArray()
    }

    private fun matchesAt(haystack: ByteArray, needle: ByteArray, from: Int): Boolean {
        for (j in needle.indices) {
            if (haystack[from + j] != needle[j]) return false
        }
        return true
    }

    companion object {
        const val MASKED = "[MASKED]"
        const val REMOVED = "[REMOVED]"

        private const val MASK_FIELDS =
            "userName|username|loginUser|userId|schoolId|school_id|userIp|acIp|ip|mac|macAddress"
        private const val REMOVE_FIELDS =
            "password|passwd|pwd|userPasswd|loginPasswd|authPasswd|authPwd|" +
                "sms|smsCode|smscode|verifyCode|verifiyCode|authCode|" +
                "ticket|cookie|setCookie|zsmKey|secret|key1|key2|key3|iv|sessionKey|token|key"

        private val JSON_MASK = Regex(
            "\"($MASK_FIELDS)\"\\s*:\\s*\"([^\"]*)\"",
            setOf(RegexOption.IGNORE_CASE),
        )
        private val JSON_REMOVE = Regex(
            "\"($REMOVE_FIELDS)\"\\s*:\\s*(\"[^\"]*\"|[-0-9.]+|true|false|null)",
            setOf(RegexOption.IGNORE_CASE),
        )
        private val QUERY_REMOVE = Regex(
            "(?i)\\b(password|passwd|pwd|userPasswd|loginPasswd|authPasswd|sms|smsCode|smscode|" +
                "verifyCode|verifiyCode|authCode|ticket|cookie|zsmKey|iv|token|secret|key)=([^&\\s\"']*)",
        )
        private val XML_TAG_REMOVE = Regex(
            "(?i)<(password|passwd|pwd|sms|smsCode|ticket|cookie|zsmKey|iv|token|secret|key1|key2|key3)" +
                "(\\s[^>]*)?>(.*?)</\\1>",
        )
        private val XML_ATTR_REMOVE = Regex(
            "(?i)\\b(password|passwd|pwd|sms|smsCode|ticket|cookie|zsmKey|iv|token|secret|key)" +
                "\\s*=\\s*\"[^\"]*\"",
        )
        private val HEADER_REMOVE = Regex("(?i)(Cookie|Set-Cookie)\\s*[:=]\\s*[^\\r\\n]*")

        private val RAW_PHONE = Regex("1[3-9]\\d{9}")
        private val RAW_IP = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b")
        private val RAW_MAC = Regex("(?i)\\b[0-9a-f]{2}([:-])[0-9a-f]{2}(?:\\1[0-9a-f]{2}){4}\\b")

        /**
         * Standard redaction configuration: field rules plus every real static
         * key/IV from production [KeyData] as byte-level removal patterns.
         */
        fun standard(extraRemoveValues: List<ByteArray> = emptyList()): Redactor {
            val maskRules = listOf(
                RedactionRule("json-mask-fields", JSON_MASK, RedactionAction.MASK) {
                    "\"${it.groupValues[1]}\": \"$MASKED\""
                },
                RedactionRule("raw-phone", RAW_PHONE, RedactionAction.MASK) { MASKED },
                RedactionRule("raw-ip", RAW_IP, RedactionAction.MASK) { MASKED },
                RedactionRule("raw-mac", RAW_MAC, RedactionAction.MASK) { MASKED },
            )
            val removeRules = listOf(
                RedactionRule("json-remove-fields", JSON_REMOVE, RedactionAction.REMOVE) {
                    "\"${it.groupValues[1]}\": \"$REMOVED\""
                },
                RedactionRule("query-remove-fields", QUERY_REMOVE, RedactionAction.REMOVE) {
                    "${it.groupValues[1]}=$REMOVED"
                },
                RedactionRule("xml-tag-remove-fields", XML_TAG_REMOVE, RedactionAction.REMOVE) {
                    "<${it.groupValues[1]}>$REMOVED</${it.groupValues[1]}>"
                },
                RedactionRule("xml-attr-remove-fields", XML_ATTR_REMOVE, RedactionAction.REMOVE) {
                    "${it.groupValues[1]}=\"$REMOVED\""
                },
                RedactionRule("header-remove", HEADER_REMOVE, RedactionAction.REMOVE) {
                    "${it.groupValues[1]}: $REMOVED"
                },
            )
            return Redactor(maskRules, removeRules, extractRealKeyBytes() + extraRemoveValues)
        }

        /** Raw byte sequences (ByteArray + little-endian IntArray) from [KeyData]. */
        private fun extractRealKeyBytes(): List<ByteArray> = buildList {
            for (field in KeyData::class.java.declaredFields) {
                if (!Modifier.isStatic(field.modifiers)) continue
                field.isAccessible = true
                when (val value = field.get(null)) {
                    is ByteArray -> add(value)
                    is IntArray -> {
                        val bytes = ByteArray(value.size * 4)
                        for ((i, w) in value.withIndex()) {
                            bytes[i * 4] = (w and 0xFF).toByte()
                            bytes[i * 4 + 1] = ((w shr 8) and 0xFF).toByte()
                            bytes[i * 4 + 2] = ((w shr 16) and 0xFF).toByte()
                            bytes[i * 4 + 3] = ((w shr 24) and 0xFF).toByte()
                        }
                        add(bytes)
                    }
                }
            }
        }
    }
}
