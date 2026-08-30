package com.rsplwe.esurfing

import com.rsplwe.esurfing.diagnostics.RunMode
import com.rsplwe.esurfing.fixture.CanarySecrets
import com.rsplwe.esurfing.fixture.FixtureSecretScanner
import com.rsplwe.esurfing.fixture.TestLogAppender
import com.rsplwe.esurfing.network.NetDiagnostics
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.apache.log4j.Logger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Startup canary scan (Todo 7): the CLI wiring must print NO password/SMS value
 * to console or logs — only the configuration *source* name. The `-p` residual
 * risk is surfaced once as a non-secret warning; sensitive mode prints a
 * non-secret warning only when explicitly enabled.
 */
class StartupWiringTest {

    @TempDir
    lateinit var tmp: Path

    private val logAppender = TestLogAppender()
    private val originalOut = System.out

    @AfterEach
    fun restore() {
        System.setOut(originalOut)
        Logger.getRootLogger().removeAppender(logAppender)
        Session.free()
        Session.attachZsmEvidenceSink(null)
        NetDiagnostics.resetForTest()
    }

    private fun capture(block: () -> Unit): Pair<String, List<String>> {
        val out = ByteArrayOutputStream()
        System.setOut(PrintStream(out))
        Logger.getRootLogger().addAppender(logAppender)
        block()
        return out.toString() to logAppender.messages.toList()
    }

    private fun optionsWithPassword(
        password: String = CanarySecrets.CANARY_PASSWORD_DEMO,
        source: PasswordSource = PasswordSource.CLI_FLAG,
    ): Options = Options(
        loginUser = CanarySecrets.CANARY_USER_DEMO,
        loginPassword = password,
        smsCode = "",
        passwordSource = source,
        diagnosticsRoot = tmp.resolve("diag").toString(),
        diagnosticMode = RunMode.NORMAL,
    )

    @Test
    fun cliFlagPassword_consoleAndLogs_haveZeroCanary_andShowSourceName() {
        val (console, logs) = capture { DialerApp.wire(optionsWithPassword(), emptyList()) }
        assertEquals(0, FixtureSecretScanner.scanText(console).size, "console leaked: $console")
        assertEquals(0, FixtureSecretScanner.scanText(logs.joinToString("\n")).size, "logs leaked: $logs")
        assertTrue(
            logs.any { it.contains("password source: cli_flag") },
            "logs must record the config SOURCE name; logs=$logs",
        )
    }

    @Test
    fun cliFlagPassword_printsResidualRiskWarningOnce() {
        val (console, _) = capture { DialerApp.wire(optionsWithPassword(), emptyList()) }
        assertTrue(
            console.contains("process") && console.contains("password"),
            "the -p residual risk must be called out once on startup; console=$console",
        )
        assertFalse(console.contains(CanarySecrets.CANARY_PASSWORD_DEMO), "warning must not echo the value")
    }

    @Test
    fun filePassword_consoleAndLogs_haveZeroCanary_showFileSource() {
        val opts = optionsWithPassword(source = PasswordSource.CLI_FILE)
        val (console, logs) = capture { DialerApp.wire(opts, listOf("password file perms ok")) }

        assertEquals(0, FixtureSecretScanner.scanText(console).size, "console leaked: $console")
        assertEquals(0, FixtureSecretScanner.scanText(logs.joinToString("\n")).size, "logs leaked: $logs")
        assertTrue(logs.any { it.contains("password source: cli_file") }, "source name must be logged")
    }

    @Test
    fun sensitiveMode_printsNonSecretWarning_onlyWhenExplicit() {
        val sensitive = optionsWithPassword().copy(diagnosticMode = RunMode.SENSITIVE)
        val (consoleS, _) = capture { DialerApp.wire(sensitive, emptyList()) }
        assertTrue(consoleS.contains("SENSITIVE"), "sensitive mode must print a non-secret startup warning")
        assertFalse(consoleS.contains(CanarySecrets.CANARY_PASSWORD_DEMO), "warning must not contain secrets")

        val normal = optionsWithPassword().copy(diagnosticMode = RunMode.NORMAL)
        val (consoleN, _) = capture { DialerApp.wire(normal, emptyList()) }
        assertFalse(
            consoleN.contains("SENSITIVE"),
            "NORMAL mode (the safe default) must NOT print the sensitive warning",
        )
    }

    @Test
    fun shutdownFlushEvidence_thenScanManifest_andRedactedAreClean() {
        val (console, _) = capture {
            val app = DialerApp.wire(optionsWithPassword(), emptyList())
            app.shutdownHandler.shutdown()
        }
        assertEquals(0, FixtureSecretScanner.scanText(console).size, "console leaked: $console")

        val runDir = tmp.resolve("diag")
        assertTrue(
            Files.walk(runDir).use { s -> s.anyMatch { it.fileName.toString() == "manifest.json" } },
            "a manifest.json must exist under the run root after shutdown",
        )
        val allFiles = Files.walk(runDir).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
        for (f in allFiles) {
            val hits = FixtureSecretScanner.scanText(Files.readString(f))
            assertEquals(0, hits.size, "evidence file $f leaked: $hits")
        }
    }
}