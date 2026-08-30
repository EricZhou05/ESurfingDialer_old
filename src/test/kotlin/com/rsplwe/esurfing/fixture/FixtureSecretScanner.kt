package com.rsplwe.esurfing.fixture

import com.rsplwe.esurfing.cipher.KeyData
import java.io.File
import java.lang.reflect.Modifier

/**
 * Scans arbitrary content for secret leakage.
 *
 * Detection targets:
 *  1. Every real key/IV from production [KeyData] — as raw byte sequences and
 *     as uppercase/lowercase hex strings.
 *  2. Every canary sentinel from [CanarySecrets] (which must never appear in
 *     fixtures, logs, reports or artifacts).
 *
 * Used two ways:
 *  - The scanner must HIT an injected canary (proving it actually works).
 *  - Scanning the committed fixtures must return ZERO hits (proving the
 *    fixtures contain no real key/IV and no field data).
 */
object FixtureSecretScanner {

    private data class BytePattern(val name: String, val bytes: ByteArray)

    /** Raw byte sequences extracted from [KeyData] (ByteArray and IntArray fields). */
    private val realKeyBytePatterns: List<BytePattern> = buildList {
        for (field in KeyData::class.java.declaredFields) {
            if (!Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            val value = field.get(null)
            when (value) {
                is ByteArray -> add(BytePattern(field.name, value))
                is IntArray -> {
                    // IntArray fields (XTEA-style) are words; scan as little-endian bytes.
                    val bytes = ByteArray(value.size * 4)
                    for ((i, w) in value.withIndex()) {
                        bytes[i * 4] = (w and 0xFF).toByte()
                        bytes[i * 4 + 1] = ((w shr 8) and 0xFF).toByte()
                        bytes[i * 4 + 2] = ((w shr 16) and 0xFF).toByte()
                        bytes[i * 4 + 3] = ((w shr 24) and 0xFF).toByte()
                    }
                    add(BytePattern(field.name, bytes))
                }
            }
        }
    }

    /** Hex-string renderings of every real key/IV (covers textual fixtures). */
    private val realKeyHexPatterns: List<BytePattern> = buildList {
        for (pattern in realKeyBytePatterns) {
            val lower = pattern.bytes.joinToString("") { "%02x".format(it) }
            val upper = lower.uppercase()
            add(BytePattern("${pattern.name}.hex-lower", lower.toByteArray()))
            add(BytePattern("${pattern.name}.hex-upper", upper.toByteArray()))
        }
    }

    private val canaryPatterns: List<BytePattern> = buildList {
        CanarySecrets.all.forEach { add(BytePattern("canary", it.toByteArray())) }
        add(BytePattern("canary-byte-key", CanarySecrets.CANARY_BYTE_KEY))
    }

    data class Hit(val patternName: String, val fileName: String)

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun scanSingleFile(file: File): List<Hit> {
        val content = file.readBytes()
        val hits = mutableListOf<Hit>()
        for (pattern in realKeyBytePatterns + realKeyHexPatterns + canaryPatterns) {
            var idx = 0
            while (true) {
                val found = indexOf(content, pattern.bytes, idx)
                if (found < 0) break
                hits.add(Hit(pattern.name, file.name))
                idx = found + pattern.bytes.size
            }
        }
        return hits
    }

    fun scanFile(file: File): List<Hit> = scanSingleFile(file)

    fun scanText(content: String): List<Hit> {
        val bytes = content.toByteArray()
        val hits = mutableListOf<Hit>()
        val all = realKeyBytePatterns + realKeyHexPatterns + canaryPatterns
        for (pattern in all) {
            var idx = 0
            while (true) {
                val found = indexOf(bytes, pattern.bytes, idx)
                if (found < 0) break
                hits.add(Hit(pattern.name, "<text>"))
                idx = found + pattern.bytes.size
            }
        }
        return hits
    }

    fun scanDirectory(dir: File): List<Hit> =
        dir.walkTopDown().filter { it.isFile }.flatMap(::scanSingleFile).toList()

    /** Combined report used by the "committed fixtures must be clean" test. */
    fun scanAllFixtures(): List<Hit> = scanDirectory(FixtureLoader.file("."))
}
