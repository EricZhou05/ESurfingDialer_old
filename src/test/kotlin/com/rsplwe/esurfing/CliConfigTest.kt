package com.rsplwe.esurfing

import com.rsplwe.esurfing.diagnostics.RunMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CLI/config contract tests (Todo 7):
 *  - precedence: default < environment < command line for every diagnostic setting
 *  - credential sources: restricted file / stdin preferred; legacy `-p` kept but
 *    flagged; env is last; explicit conflicts are rejected
 *  - sensitive mode requires an explicit opt-in (CLI flag or env) and never the
 *    default; invalid values and unwritable diagnostics roots fail typed
 */
class CliConfigTest {

    @TempDir
    lateinit var tmp: Path

    private fun parse(args: Array<String>, env: Map<String, String> = emptyMap(), stdin: (() -> String?)? = null): CliResult =
        CliParser(stdin ?: { null }).parse(args, env)

    private fun ok(result: CliResult): Options =
        assertIs<CliResult.Ok>(result).options

    // --- precedence matrix: default < env < CLI ---

    @Test
    fun diagnosticsRoot_default_isDisabled_whenNothingProvided() {
        val opts = ok(parse(arrayOf("-u", "U", "--password-stdin"), stdin = { "pw" }))
        assertNull(opts.diagnosticsRoot, "no root option/env must leave diagnostics disabled")
    }

    @Test
    fun diagnosticsRoot_fromEnv_whenNoCliOption() {
        val opts = ok(
            parse(
                arrayOf("-u", "U", "--password-stdin"),
                env = mapOf("ESURFING_DIAGNOSTICS_ROOT" to tmp.resolve("env-root").toString()),
                stdin = { "pw" },
            ),
        )
        assertEquals(tmp.resolve("env-root"), Path.of(opts.diagnosticsRoot!!))
    }

    @Test
    fun diagnosticsRoot_cliFlag_overridesEnv() {
        val cliRoot = tmp.resolve("cli-root")
        val opts = ok(
            parse(
                arrayOf("-u", "U", "--diagnostics-root", cliRoot.toString(), "--password-stdin"),
                env = mapOf("ESURFING_DIAGNOSTICS_ROOT" to tmp.resolve("env-root").toString()),
                stdin = { "pw" },
            ),
        )
        assertEquals(cliRoot, Path.of(opts.diagnosticsRoot!!), "CLI root must beat env root")
    }

    @Test
    fun diagnosticMode_default_isNormal() {
        val opts = ok(parse(arrayOf("-u", "U", "--password-stdin", "--diagnostics-root", tmp.toString()), stdin = { "pw" }))
        assertEquals(RunMode.NORMAL, opts.diagnosticMode, "sensitive capture must be off by default")
    }

    @Test
    fun diagnosticMode_fromEnv_isSupported() {
        val opts = ok(
            parse(
                arrayOf("-u", "U", "--password-stdin", "--diagnostics-root", tmp.toString()),
                env = mapOf("ESURFING_DIAGNOSTIC_MODE" to "sensitive"),
                stdin = { "pw" },
            ),
        )
        assertEquals(RunMode.SENSITIVE, opts.diagnosticMode, "explicit env opt-in must select SENSITIVE")
    }

    @Test
    fun diagnosticMode_cliFlag_overridesEnv() {
        val opts = ok(
            parse(
                arrayOf("-u", "U", "--diagnostic-mode", "normal", "--diagnostics-root", tmp.toString(), "--password-stdin"),
                env = mapOf("ESURFING_DIAGNOSTIC_MODE" to "sensitive"),
                stdin = { "pw" },
            ),
        )
        assertEquals(RunMode.NORMAL, opts.diagnosticMode, "CLI mode must beat env mode")
    }

    @Test
    fun payloadLimit_default_env_cli_precedence() {
        val defaultOpt = ok(parse(arrayOf("-u", "U", "--password-stdin", "--diagnostics-root", tmp.toString()), stdin = { "pw" }))
        assertEquals(Options.DEFAULT_MAX_PAYLOAD_BYTES, defaultOpt.maxPayloadBytes, "default payload limit")

        val envOpt = ok(
            parse(
                arrayOf("-u", "U", "--password-stdin", "--diagnostics-root", tmp.toString()),
                env = mapOf("ESURFING_PAYLOAD_LIMIT" to "4096"),
                stdin = { "pw" },
            ),
        )
        assertEquals(4096L, envOpt.maxPayloadBytes, "env limit must beat the default")

        val cliOpt = ok(
            parse(
                arrayOf("-u", "U", "--payload-limit", "8192", "--diagnostics-root", tmp.toString(), "--password-stdin"),
                env = mapOf("ESURFING_PAYLOAD_LIMIT" to "4096"),
                stdin = { "pw" },
            ),
        )
        assertEquals(8192L, cliOpt.maxPayloadBytes, "CLI limit must beat env limit")
    }

    @Test
    fun retention_default_env_cli_precedence() {
        val defaultOpt = ok(parse(arrayOf("-u", "U", "--password-stdin", "--diagnostics-root", tmp.toString()), stdin = { "pw" }))
        assertEquals(Options.DEFAULT_RETENTION_MAX_RUNS, defaultOpt.retentionMaxRuns)
        assertEquals(Options.DEFAULT_RETENTION_MAX_AGE_DAYS, defaultOpt.retentionMaxAgeDays)

        val envOpt = ok(
            parse(
                arrayOf("-u", "U", "--password-stdin", "--diagnostics-root", tmp.toString()),
                env = mapOf("ESURFING_RETENTION" to "10:20"),
                stdin = { "pw" },
            ),
        )
        assertEquals(10, envOpt.retentionMaxRuns)
        assertEquals(20, envOpt.retentionMaxAgeDays)

        val cliOpt = ok(
            parse(
                arrayOf("-u", "U", "--retention", "5:7", "--diagnostics-root", tmp.toString(), "--password-stdin"),
                env = mapOf("ESURFING_RETENTION" to "10:20"),
                stdin = { "pw" },
            ),
        )
        assertEquals(5, cliOpt.retentionMaxRuns)
        assertEquals(7, cliOpt.retentionMaxAgeDays)
    }

    // --- invalid values ---

    @Test
    fun invalidMode_returnsTypedError() {
        val result = parse(arrayOf("-u", "U", "--diagnostic-mode", "paranoid", "--password-stdin"), stdin = { "pw" })
        assertIs<CliResult.Error>(result)
        assertEquals("INVALID_MODE", result.code)
    }

    @Test
    fun invalidPayload_returnsTypedError() {
        val result = parse(arrayOf("-u", "U", "--payload-limit", "abc", "--password-stdin"), stdin = { "pw" })
        assertIs<CliResult.Error>(result)
        assertEquals("INVALID_PAYLOAD_LIMIT", result.code)
    }

    @Test
    fun invalidRetention_returnsTypedError() {
        val result = parse(arrayOf("-u", "U", "--retention", "30", "--password-stdin"), stdin = { "pw" })
        assertIs<CliResult.Error>(result)
        assertEquals("INVALID_RETENTION", result.code)
    }

    // --- credential sources ---

    @Test
    fun noPassword_returnsTypedError() {
        val result = parse(arrayOf("-u", "U"))
        assertIs<CliResult.Error>(result)
        assertEquals("NO_PASSWORD", result.code)
    }

    @Test
    fun noUser_returnsTypedError() {
        val result = parse(arrayOf("--password-stdin"), stdin = { "pw" })
        assertIs<CliResult.Error>(result)
        assertEquals("NO_USER", result.code)
    }

    @Test
    fun passwordFile_readsSingleLine() {
        val file = tmp.resolve("pass")
        Files.writeString(file, "CAnary-File-P@ss-001\nignored-second-line")
        val opts = ok(parse(arrayOf("-u", "U", "--password-file", file.toString())))
        assertEquals("CAnary-File-P@ss-001", opts.loginPassword)
        assertEquals(PasswordSource.CLI_FILE, opts.passwordSource)
    }

    @Test
    fun passwordFile_missing_returnsTypedError() {
        val result = parse(arrayOf("-u", "U", "--password-file", tmp.resolve("nope").toString()))
        assertIs<CliResult.Error>(result)
        assertEquals("PASSWORD_FILE_UNREADABLE", result.code)
    }

    @Test
    fun passwordStdin_readsLine() {
        val opts = ok(parse(arrayOf("-u", "U", "--password-stdin"), stdin = { "S-tdin-P@ss-002" }))
        assertEquals("S-tdin-P@ss-002", opts.loginPassword)
        assertEquals(PasswordSource.STDIN, opts.passwordSource)
    }

    @Test
    fun legacyPasswordFlag_isKept_butSourcesCliFlag() {
        val opts = ok(parse(arrayOf("-u", "U", "-p", "CAnary-Legacy-P@ss-003")))
        assertEquals("CAnary-Legacy-P@ss-003", opts.loginPassword)
        assertEquals(PasswordSource.CLI_FLAG, opts.passwordSource)
    }

    @Test
    fun envPassword_isLastResort_andSourcesEnv() {
        val opts = ok(parse(arrayOf("-u", "U"), env = mapOf("ESURFING_PASSWORD" to "CAnary-Env-P@ss-004")))
        assertEquals("CAnary-Env-P@ss-004", opts.loginPassword)
        assertEquals(PasswordSource.ENV, opts.passwordSource)
    }

    @Test
    fun cliPasswordSource_beatsEnvPassword() {
        val opts = ok(
            parse(
                arrayOf("-u", "U", "--password-stdin"),
                env = mapOf("ESURFING_PASSWORD" to "CAnary-Env-P@ss-004"),
                stdin = { "CAnary-Stdin-P@ss-005" },
            ),
        )
        assertEquals("CAnary-Stdin-P@ss-005", opts.loginPassword, "CLI source must beat the env password")
        assertEquals(PasswordSource.STDIN, opts.passwordSource)
    }

    @Test
    fun multipleCliPasswordSources_areRejected() {
        val file = tmp.resolve("pass")
        Files.writeString(file, "pw")
        val result = parse(arrayOf("-u", "U", "-p", "legacy", "--password-file", file.toString()))
        assertIs<CliResult.Error>(result)
        assertEquals("CONFLICT_PASSWORD", result.code)
    }

    @Test
    fun passwordFile_groupReadable_warns0600() {
        if (!Files.exists(tmp)) return
        val file = tmp.resolve("pass")
        Files.writeString(file, "CAnary-Perms-P@ss-006")
        try {
            Files.setPosixFilePermissions(
                file,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                ),
            )
        } catch (e: UnsupportedOperationException) {
            return // non-POSIX: best-effort residual risk, nothing to assert
        }
        val result = parse(arrayOf("-u", "U", "--password-file", file.toString()))
        val okResult = assertIs<CliResult.Ok>(result)
        assertEquals("CAnary-Perms-P@ss-006", okResult.options.loginPassword)
        assertTrue(
            okResult.warnings.any { it.contains("0600") },
            "group/other-readable password file must warn chmod 600; got ${okResult.warnings}",
        )
    }

    // --- unwritable diagnostics root ---

    @Test
    fun unwritableDiagnosticsRoot_returnsTypedError() {
        val blocker = tmp.resolve("blocker")
        Files.writeString(blocker, "x")
        val rootUnderFile = blocker.resolve("sub")
        val result = parse(
            arrayOf("-u", "U", "--password-stdin", "--diagnostics-root", rootUnderFile.toString()),
            stdin = { "pw" },
        )
        assertIs<CliResult.Error>(result)
        assertEquals("DIAGNOSTICS_ROOT_UNWRITABLE", result.code)
    }

    @Test
    fun smsCode_flag_passesThrough() {
        val opts = ok(parse(arrayOf("-u", "U", "-p", "pw", "-s", "CAnary-SmsC0de-135790")))
        assertEquals("CAnary-SmsC0de-135790", opts.smsCode)
    }
}