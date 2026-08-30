package com.rsplwe.esurfing

import com.rsplwe.esurfing.diagnostics.RunMode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.apache.commons.cli.CommandLine
import org.apache.commons.cli.CommandLineParser
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.Option
import org.apache.commons.cli.ParseException
import org.apache.commons.cli.Options as CliOptions

/**
 * Typed result of CLI/environment configuration resolution.
 *
 * [Ok] carries the resolved [Options] plus non-secret startup warnings (e.g.
 * the legacy `-p` residual risk); [Error] carries a stable machine code and a
 * human message so [DialerApp] can fail safely without printing secrets.
 */
sealed interface CliResult {
    data class Ok(val options: Options, val warnings: List<String>) : CliResult
    data class Error(val code: String, val message: String) : CliResult
}

/**
 * Parses the command line and environment into typed [Options].
 *
 * Precedence (documented in README):
 *   default < environment < command line
 * for every diagnostics setting (root / mode / payload limit / retention) and
 * for credentials CLI sources beat `ESURFING_PASSWORD`. Exactly **one** CLI
 * password source may be given (`--password-file`, `--password-stdin`, `-p`);
 * more than one is a hard [CliResult.Error].
 *
 * The CLI parser is pure config resolution — it never logs credentials, only
 * the resolved [PasswordSource] name.
 */
class CliParser(private val stdinProvider: () -> String? = { readlnOrNull() }) {

    fun parse(args: Array<String>, env: Map<String, String> = System.getenv()): CliResult {
        val cmd = try {
            DefaultParser().parse(buildOptions(), args)
        } catch (e: ParseException) {
            return CliResult.Error("CLI_PARSE", e.message ?: "invalid command line")
        }

        val user = cmd.getOptionValue("user")
        if (user.isNullOrBlank()) {
            return CliResult.Error("NO_USER", "Login user is required (-u/--user)")
        }

        val passwordResolution = resolvePassword(cmd, env)
        if (passwordResolution is PasswordResolution.Error) {
            return CliResult.Error(passwordResolution.code, passwordResolution.message)
        }
        val resolvedPassword = passwordResolution as PasswordResolution.Ok

        val diagnostics = resolveDiagnostics(cmd, env)
        if (diagnostics.error != null) {
            return diagnostics.error
        }

        return CliResult.Ok(
            options = Options(
                loginUser = user,
                loginPassword = resolvedPassword.password,
                smsCode = cmd.getOptionValue("sms") ?: "",
                passwordSource = resolvedPassword.source,
                diagnosticsRoot = diagnostics.root,
                diagnosticMode = diagnostics.mode,
                maxPayloadBytes = diagnostics.payloadLimit,
                retentionMaxRuns = diagnostics.retentionMaxRuns,
                retentionMaxAgeDays = diagnostics.retentionMaxAgeDays,
            ),
            warnings = resolvedPassword.warnings + diagnostics.warnings,
        )
    }

    // --- password sources ---

    private sealed interface PasswordResolution {
        data class Ok(
            val password: String,
            val source: PasswordSource,
            val warnings: List<String>,
        ) : PasswordResolution

        data class Error(val code: String, val message: String) : PasswordResolution
    }

    private fun resolvePassword(cmd: CommandLine, env: Map<String, String>): PasswordResolution {
        val file = cmd.getOptionValue("password-file")
        val useStdin = cmd.hasOption("password-stdin")
        val legacy = cmd.getOptionValue("password")
        val cliSources = listOfNotNull(
            file?.let { "-p" },
            if (legacy != null) "-p" else null,
            if (useStdin) "--password-stdin" else null,
        )
        if (cliSources.size > 1) {
            return PasswordResolution.Error(
                "CONFLICT_PASSWORD",
                "Conflicting password sources: ${cliSources.joinToString(", ")}; choose exactly one",
            )
        }

        if (!file.isNullOrBlank()) {
            val read = readPasswordFile(Path.of(file))
            if (read.error != null) return PasswordResolution.Error(read.error.code, read.error.message)
            return PasswordResolution.Ok(read.password!!, PasswordSource.CLI_FILE, read.permissionWarnings)
        }
        if (useStdin) {
            val line = stdinProvider()?.trim() ?: ""
            if (line.isBlank()) return PasswordResolution.Error("NO_PASSWORD", "No password was provided on stdin")
            return PasswordResolution.Ok(line, PasswordSource.STDIN, emptyList())
        }
        if (legacy != null && legacy.isNotBlank()) {
            return PasswordResolution.Ok(
                legacy,
                PasswordSource.CLI_FLAG,
                listOf("Password supplied via -p/--password is visible to other processes (ps). Prefer --password-file or --password-stdin."),
            )
        }
        val envPassword = env["ESURFING_PASSWORD"]
        if (!envPassword.isNullOrBlank()) {
            return PasswordResolution.Ok(
                envPassword,
                PasswordSource.ENV,
                listOf("Password supplied via ESURFING_PASSWORD is visible via /proc/<pid>/environ. Prefer --password-file or --password-stdin."),
            )
        }
        return PasswordResolution.Error("NO_PASSWORD", "No login password provided; use -p, --password-file, --password-stdin or ESURFING_PASSWORD")
    }

    private data class PasswordFileRead(
        val password: String? = null,
        val error: CliResult.Error? = null,
        val permissionWarnings: List<String> = emptyList(),
    )

    private fun readPasswordFile(file: Path): PasswordFileRead {
        if (!Files.isRegularFile(file)) {
            return PasswordFileRead(error = CliResult.Error("PASSWORD_FILE_UNREADABLE", "Password file does not exist or is not a regular file: $file"))
        }
        val line = try {
            Files.newBufferedReader(file).use { it.readLine()?.trim() ?: "" }
        } catch (e: IOException) {
            return PasswordFileRead(error = CliResult.Error("PASSWORD_FILE_UNREADABLE", "Cannot read password file $file: ${e.message}"))
        }
        if (line.isBlank()) {
            return PasswordFileRead(error = CliResult.Error("PASSWORD_FILE_EMPTY", "Password file $file is empty"))
        }
        val warnings = mutableListOf<String>()
        try {
            val perms = Files.getPosixFilePermissions(file)
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                warnings.add("Password file $file is group/other-readable; run chmod 0600 to restrict (POSIX best-effort).")
            }
        } catch (e: UnsupportedOperationException) {
            warnings.add("Password file permission check unavailable on this filesystem (best-effort).")
        } catch (e: IOException) {
            warnings.add("Password file permission check failed (best-effort).")
        }
        return PasswordFileRead(password = line, permissionWarnings = warnings)
    }

    // --- diagnostics configuration ---

    private data class DiagnosticsResolution(
        val root: String?,
        val mode: RunMode,
        val payloadLimit: Long,
        val retentionMaxRuns: Int,
        val retentionMaxAgeDays: Int,
        val warnings: List<String>,
        val error: CliResult.Error? = null,
    )

    private fun resolveDiagnostics(cmd: CommandLine, env: Map<String, String>): DiagnosticsResolution {
        val root = cmd.getOptionValue("diagnostics-root")?.takeIf { it.isNotBlank() }
            ?: env["ESURFING_DIAGNOSTICS_ROOT"]?.takeIf { it.isNotBlank() }

        val modeRaw = cmd.getOptionValue("diagnostic-mode")?.takeIf { it.isNotBlank() }
            ?: env["ESURFING_DIAGNOSTIC_MODE"]?.takeIf { it.isNotBlank() }
            ?: "normal"
        val mode = when (modeRaw.lowercase()) {
            "normal" -> RunMode.NORMAL
            "sensitive" -> RunMode.SENSITIVE
            else -> return DiagnosticsResolution(
                root, RunMode.NORMAL, Options.DEFAULT_MAX_PAYLOAD_BYTES,
                Options.DEFAULT_RETENTION_MAX_RUNS, Options.DEFAULT_RETENTION_MAX_AGE_DAYS, emptyList(),
                error = CliResult.Error("INVALID_MODE", "Invalid diagnostic mode '$modeRaw'; use normal|sensitive"),
            )
        }

        val payloadRaw = cmd.getOptionValue("payload-limit")?.takeIf { it.isNotBlank() }
            ?: env["ESURFING_PAYLOAD_LIMIT"]?.takeIf { it.isNotBlank() }
            ?: Options.DEFAULT_MAX_PAYLOAD_BYTES.toString()
        val payloadLimit = payloadRaw.toLongOrNull()?.takeIf { it > 0 }
            ?: return DiagnosticsResolution(
                root, mode, Options.DEFAULT_MAX_PAYLOAD_BYTES,
                Options.DEFAULT_RETENTION_MAX_RUNS, Options.DEFAULT_RETENTION_MAX_AGE_DAYS, emptyList(),
                error = CliResult.Error("INVALID_PAYLOAD_LIMIT", "Invalid payload limit '$payloadRaw'; use a positive byte count"),
            )

        val retentionRaw = cmd.getOptionValue("retention")?.takeIf { it.isNotBlank() }
            ?: env["ESURFING_RETENTION"]?.takeIf { it.isNotBlank() }
            ?: "${Options.DEFAULT_RETENTION_MAX_RUNS}:${Options.DEFAULT_RETENTION_MAX_AGE_DAYS}"
        val retentionParts = retentionRaw.split(":", limit = 2)
        val runs = retentionParts.getOrNull(0)?.toIntOrNull()
        val age = retentionParts.getOrNull(1)?.toIntOrNull()
        if (retentionParts.size != 2 || runs == null || age == null || runs <= 0 || age <= 0) {
            return DiagnosticsResolution(
                root, mode, payloadLimit,
                Options.DEFAULT_RETENTION_MAX_RUNS, Options.DEFAULT_RETENTION_MAX_AGE_DAYS, emptyList(),
                error = CliResult.Error("INVALID_RETENTION", "Invalid retention '$retentionRaw'; use maxRuns:maxAgeDays (positive integers)"),
            )
        }

        val warnings = mutableListOf<String>()
        if (root != null) {
            try {
                Files.createDirectories(Path.of(root))
            } catch (e: IOException) {
                return DiagnosticsResolution(
                    root, mode, payloadLimit, runs, age, emptyList(),
                    error = CliResult.Error("DIAGNOSTICS_ROOT_UNWRITABLE", "Cannot write diagnostics root $root: ${e.message}"),
                )
            }
            if (mode == RunMode.SENSITIVE) {
                warnings.add("SENSITIVE diagnostic mode enabled: raw portal/ZSM/cipher/XML payloads are captured under the diagnostics root. Keep that directory restricted and encrypt before export.")
            }
        }
        return DiagnosticsResolution(root, mode, payloadLimit, runs, age, warnings)
    }

    /** Commons-CLI option set shared by [parse] and [DialerApp] help output. */
    fun buildOptions(): CliOptions {
        val cli = CliOptions()
        cli.addOption(
            Option.builder("u").longOpt("user").argName("user").hasArg().desc("Login user (phone number or other)").build(),
        )
        cli.addOption(
            Option.builder("p").longOpt("password").argName("password").hasArg().desc("Login password (legacy; visible via ps — prefer --password-file/--password-stdin)").build(),
        )
        cli.addOption(Option.builder("s").longOpt("sms").argName("sms").hasArg().desc("Pre-entered verification code").build())
        cli.addOption(Option.builder().longOpt("password-file").argName("path").hasArg().desc("Read password from first line of a restricted file").build())
        cli.addOption(Option.builder().longOpt("password-stdin").desc("Read password from one line of standard input").build())
        cli.addOption(Option.builder().longOpt("diagnostics-root").argName("path").hasArg().desc("Enable evidence storage under this directory").build())
        cli.addOption(Option.builder().longOpt("diagnostic-mode").argName("mode").hasArg().desc("normal|sensitive (default normal; sensitive requires explicit opt-in)").build())
        cli.addOption(Option.builder().longOpt("payload-limit").argName("bytes").hasArg().desc("Max payload bytes per evidence write").build())
        cli.addOption(Option.builder().longOpt("retention").argName("runs:days").hasArg().desc("Evidence retention as maxRuns:maxAgeDays").build())
        return cli
    }
}