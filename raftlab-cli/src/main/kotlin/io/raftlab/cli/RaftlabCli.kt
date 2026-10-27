package io.raftlab.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.Localization
import com.github.ajalt.clikt.output.ParameterFormatter
import com.github.ajalt.clikt.parameters.options.versionOption

internal class RaftlabCommand : CoreCliktCommand(name = "raftlab") {
    init {
        versionOption(RaftlabVersion.current(), names = setOf("--version"), message = { "raftlab version $it\n" })
    }

    override fun help(context: Context): String =
        "Raft lab: simulator scenarios, fuzzing, diagram export, docs, a real TCP node and a KV client. " +
            "Exit codes: 0 success, 1 check failed, 2 usage error."

    override fun run() = Unit
}

internal object RaftlabCli {
    fun command(env: CliEnvironment): RaftlabCommand =
        RaftlabCommand().subcommands(
            ScenarioCommand(env),
            ExportCommand(env),
            DocsCommand(env),
            FuzzCommand(env),
            NodeCommand(env),
            KvCommand(env),
        )

    fun run(
        args: List<String>,
        env: CliEnvironment,
    ): Int {
        val root = command(env)
        return try {
            root.parse(args)
            CliDefaults.EXIT_OK
        } catch (e: PrintHelpMessage) {
            val text = (e.context?.command ?: root).getFormattedHelp(e) ?: ""
            if (e.error) {
                env.err.printText(text)
                CliDefaults.EXIT_USAGE
            } else {
                env.out.printText(text)
                CliDefaults.EXIT_OK
            }
        } catch (e: PrintMessage) {
            if (e.printError) env.err.print(e.message) else env.out.print(e.message)
            e.statusCode
        } catch (e: ProgramResult) {
            e.statusCode
        } catch (e: UsageError) {
            env.err.println("error: " + oneLine(e))
            CliDefaults.EXIT_USAGE
        } catch (e: CliktError) {
            env.err.println("error: ${e.message}")
            CliDefaults.EXIT_USAGE
        } finally {
            env.out.flush()
            env.err.flush()
        }
    }

    private fun oneLine(e: UsageError): String =
        e
            .formatMessage(e.context?.localization ?: object : Localization {}, ParameterFormatter.Plain)
            .lines()
            .filter { it.isNotBlank() }
            .joinToString("; ")
}
