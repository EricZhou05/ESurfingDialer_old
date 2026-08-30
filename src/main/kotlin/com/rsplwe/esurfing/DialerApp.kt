package com.rsplwe.esurfing

import com.rsplwe.esurfing.diagnostics.RunMode
import java.nio.file.Path
import org.apache.commons.cli.HelpFormatter
import org.apache.log4j.Logger
import kotlin.system.exitProcess

object DialerApp {

    private val logger: Logger = Logger.getLogger(DialerApp::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        val parser = CliParser()
        when (val result = parser.parse(args)) {
            is CliResult.Error -> {
                logger.error(result.message)
                println(result.message)
                HelpFormatter().printHelp("ESurfingDialer", parser.buildOptions())
                exitProcess(1)
            }
            is CliResult.Ok -> {
                val app = wire(result.options, result.warnings)
                Runtime.getRuntime().addShutdownHook(Thread { app.shutdownHandler.shutdown() })
                States.refreshStates()
                app.client.run()
            }
        }
    }

    /**
     * Wires a typed [Options] into a running client: prints the non-secret
     * startup warnings (legacy `-p` residual risk, sensitive-mode notice),
     * opens the diagnostics evidence run when configured, and returns the
     * assembled [PreparedApp]. Never prints a credential value — only source
     * names are logged.
     */
    internal fun wire(options: Options, warnings: List<String>): PreparedApp {
        val consoleWarnings = mutableListOf<String>()
        consoleWarnings += warnings
        when (options.passwordSource) {
            PasswordSource.CLI_FLAG -> consoleWarnings +=
                "Password provided via -p/--password is visible to other processes (ps). Prefer --password-file or --password-stdin."
            PasswordSource.ENV -> consoleWarnings +=
                "Password provided via ESURFING_PASSWORD is visible via /proc/<pid>/environ. Prefer --password-file or --password-stdin."
            PasswordSource.CLI_FILE, PasswordSource.STDIN -> Unit // safe sources, no extra warning
        }
        if (options.diagnosticsRoot != null && options.diagnosticMode == RunMode.SENSITIVE) {
            consoleWarnings +=
                "SENSITIVE diagnostic mode enabled: raw portal/ZSM/cipher/XML payloads are captured under " +
                    (options.diagnosticsRoot ?: "") +
                    ". Keep that directory restricted and encrypt before export."
        }
        consoleWarnings.forEach { println("WARNING: $it") }

        // Config source names only — never credential values.
        logger.info("password source: ${options.passwordSource.name.lowercase()}")
        logger.info("diagnostics: ${if (options.diagnosticsRoot == null) "disabled" else "enabled"}")

        val runner = options.diagnosticsRoot?.let { root ->
            DiagnosticsRunner.open(
                root = Path.of(root),
                mode = options.diagnosticMode,
                payloadLimit = options.maxPayloadBytes,
                retentionMaxRuns = options.retentionMaxRuns,
                retentionMaxAgeDays = options.retentionMaxAgeDays,
            ).also { it.attachToSession() }
        }
        val client = Client(options)
        return PreparedApp(client = client, runner = runner, shutdownHandler = ShutdownHandler(client, runner))
    }

    /** The assembled runtime handed to [main]; also the seam tests exercise. */
    internal class PreparedApp(
        val client: Client,
        val runner: DiagnosticsRunner?,
        val shutdownHandler: ShutdownHandler,
    )
}