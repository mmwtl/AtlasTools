package com.mmwtl.atlascodecfix

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reads and changes the ART compiler filter of [AotCatalog] applications. Compilation runs in the
 * package manager (`cmd package compile`), so it needs neither root nor any change under
 * `/vendor`. All sequences share [DeviceOperationLock] with the codec profile transaction.
 */
class AotCompileRepository(
    private val adb: AdbCommandExecutor,
    private val packages: List<AotPackage> = AotCatalog.PACKAGES
) {
    fun interface ProgressListener {
        suspend fun onCompiling(pkg: AotPackage, index: Int, total: Int)
    }

    suspend fun queryStatus(): AotStatusResult = withContext(Dispatchers.IO) {
        DeviceOperationLock.mutex.withLock { queryStatusLocked(packages) }
    }

    /** Compiles every installed catalog package that is not yet compiled with `speed`. */
    suspend fun compileOutdated(
        progress: ProgressListener = ProgressListener { _, _, _ -> }
    ): AotCompileResult = withContext(Dispatchers.IO) {
        DeviceOperationLock.mutex.withLock {
            val before = queryStatusLocked(packages)
            if (!before.commandSuccess) {
                return@withLock AotCompileResult(statuses = before.packages, statusOutput = before.output)
            }
            val targets = before.packages
                .filter { it.state == AotCompileState.NOT_COMPILED }
                .map(AotPackageStatus::pkg)
            compileLocked(before, targets, progress)
        }
    }

    /** Recompiles one package with `speed`, even when it already reports that filter. */
    suspend fun compilePackage(
        pkg: AotPackage,
        progress: ProgressListener = ProgressListener { _, _, _ -> }
    ): AotCompileResult = withContext(Dispatchers.IO) {
        require(pkg in packages) { "Package is not in the AOT catalog: ${pkg.packageName}" }
        DeviceOperationLock.mutex.withLock {
            val before = queryStatusLocked(packages)
            if (!before.commandSuccess) {
                return@withLock AotCompileResult(statuses = before.packages, statusOutput = before.output)
            }
            val installed = before.packages.first { it.pkg == pkg }.state != AotCompileState.NOT_INSTALLED
            compileLocked(before, if (installed) listOf(pkg) else emptyList(), progress)
        }
    }

    private suspend fun compileLocked(
        before: AotStatusResult,
        targets: List<AotPackage>,
        progress: ProgressListener
    ): AotCompileResult {
        val statuses = before.packages.associateByTo(linkedMapOf()) { it.pkg }
        val outcomes = mutableListOf<AotPackageCompileOutcome>()
        var transportFailed = false
        for ((index, pkg) in targets.withIndex()) {
            progress.onCompiling(pkg, index + 1, targets.size)
            val run = executeCatching(buildCompileCommand(pkg), COMPILE_TIMEOUT_MS)
            val after = queryStatusLocked(listOf(pkg))
            val status = after.packages.single()
            statuses[pkg] = status
            outcomes += AotPackageCompileOutcome(
                pkg = pkg,
                success = run.succeeded && after.commandSuccess &&
                    status.state == AotCompileState.COMPILED,
                output = run.displayOutput
            )
            // A transport failure or timeout drops the connection; continuing would only turn
            // every remaining package into the same failure.
            if (run.failure != null || !after.commandSuccess) {
                transportFailed = true
                break
            }
        }
        return AotCompileResult(
            statuses = statuses.values.toList(),
            statusOutput = before.output,
            statusSuccess = true,
            outcomes = outcomes,
            transportFailed = transportFailed,
            requested = targets
        )
    }

    private suspend fun queryStatusLocked(targets: List<AotPackage>): AotStatusResult {
        val result = executeCatching(buildStatusCommand(), STATUS_TIMEOUT_MS)
        val output = result.displayOutput
        val success = result.succeeded && AotDexoptStatusParser.hasEndMarker(result.stdout)
        return AotStatusResult(
            packages = if (success) {
                AotDexoptStatusParser.parse(result.stdout, targets)
            } else {
                targets.map { AotPackageStatus(it, AotCompileState.UNKNOWN) }
            },
            output = output,
            commandSuccess = success
        )
    }

    private suspend fun executeCatching(command: String, timeoutMs: Long): AdbCommandResult {
        return try {
            adb.execute(command, timeoutMs)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "ADB command invocation failed", t)
            AdbCommandResult.failure(
                AdbCommandFailureKind.TRANSPORT,
                t.message ?: t.javaClass.simpleName
            )
        }
    }

    internal companion object {
        private const val TAG = "AtlasTools"
        internal const val STATUS_TIMEOUT_MS = 60_000L
        internal const val COMPILE_TIMEOUT_MS = 300_000L
        internal const val COMPILER_FILTER = "speed"

        internal fun buildStatusCommand(): String =
            "dumpsys package dexopt 2>&1; " +
                "echo \"${AotDexoptStatusParser.DUMP_EXIT_MARKER}\$?\"; " +
                "echo ${AotDexoptStatusParser.END_MARKER}"

        internal fun buildCompileCommand(pkg: AotPackage): String =
            "cmd package compile -m $COMPILER_FILTER -f ${pkg.packageName.shellQuote()}"

        private fun String.shellQuote(): String = "'" + replace("'", "'\"'\"'") + "'"
    }
}

data class AotStatusResult(
    val packages: List<AotPackageStatus>,
    val output: String,
    val commandSuccess: Boolean
)

data class AotPackageCompileOutcome(
    val pkg: AotPackage,
    val success: Boolean,
    val output: String
)

data class AotCompileResult(
    val statuses: List<AotPackageStatus>,
    val statusOutput: String,
    val statusSuccess: Boolean = false,
    val outcomes: List<AotPackageCompileOutcome> = emptyList(),
    val transportFailed: Boolean = false,
    val requested: List<AotPackage> = emptyList()
) {
    val compiled: List<AotPackage>
        get() = outcomes.filter(AotPackageCompileOutcome::success).map(AotPackageCompileOutcome::pkg)

    /** Requested packages that failed or were never attempted because the transport broke. */
    val failed: List<AotPackage>
        get() = requested - compiled.toSet()

    val success: Boolean
        get() = statusSuccess && !transportFailed && failed.isEmpty()

    /** Connection-level problems are worth retrying; a package that dex2oat rejects is not. */
    val retryable: Boolean
        get() = !statusSuccess || transportFailed
}
