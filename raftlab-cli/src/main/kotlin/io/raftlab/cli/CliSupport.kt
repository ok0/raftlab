package io.raftlab.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import java.io.PrintStream

internal class CliEnvironment(
    val out: PrintStream,
    val err: PrintStream,
    val stopSignal: () -> StopSignal = { ProcessStopSignal.install() },
) {
    companion object {
        fun system(): CliEnvironment = CliEnvironment(System.out, System.err)
    }
}

internal object CliDefaults {
    const val SEED: Long = 42L
    const val FUZZ_SEEDS: Long = 1_000L
    const val FUZZ_START: Long = 0L
    const val KV_TIMEOUT_MILLIS: Long = 10_000L
    const val EXIT_OK: Int = 0
    const val EXIT_FAILED: Int = 1
    const val EXIT_USAGE: Int = 2
}

internal fun usageError(message: String): Nothing = throw UsageError(message)

internal fun checkFailed(): Nothing = throw ProgramResult(CliDefaults.EXIT_FAILED)

internal fun PrintStream.printText(text: String) {
    print(text)
    if (!text.endsWith("\n")) print("\n")
}
