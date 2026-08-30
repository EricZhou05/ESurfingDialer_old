package com.rsplwe.esurfing.fixture

import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Test helper that loads resources from src/test/resources/fixtures/ by a
 * relative path such as "zsm/valid.zsm".
 */
object FixtureLoader {

    private fun resourcePath(relative: String): String {
        val normalized = relative.trimStart('/')
        val url = requireNotNull(
            javaClass.classLoader.getResource("fixtures/$normalized")
        ) { "Missing fixture: fixtures/$normalized" }
        // Ensure the resolved URL is a file URL so binary fixtures load exactly.
        check(url.protocol == "file") { "Fixture must be a file resource: $url" }
        return URLDecoder.decode(url.path, StandardCharsets.UTF_8)
    }

    fun bytes(relative: String): ByteArray = File(resourcePath(relative)).readBytes()

    fun text(relative: String): String =
        File(resourcePath(relative)).readText(StandardCharsets.UTF_8)

    fun file(relative: String): File = File(resourcePath(relative))

    /** Returns every fixture file under the given relative directory. */
    fun filesUnder(relativeDir: String): List<File> {
        val dir = File(resourcePath(relativeDir))
        require(dir.isDirectory) { "Not a fixture directory: ${dir.path}" }
        return dir.walkTopDown().filter { it.isFile }.sortedBy { it.name }.toList()
    }

    fun allFixtureFiles(): List<File> {
        val root = File(resourcePath("."))
        return root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
    }
}
