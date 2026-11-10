package com.mmwtl.atlascodecfix

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class AotCompileRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val launcher = AotPackage("com.geely.atlaslauncher", "Launcher")
    private val gsplit = AotPackage("com.salat.gsplit", "GSplit")
    private val terminal = AotPackage("com.mmwtl.atlasterminal", "Terminal")
    private val catalog = listOf(launcher, gsplit, terminal)

    @Test
    fun compileOutdatedCompilesOnlyInstalledStalePackagesAndVerifiesEach() = runBlocking {
        val device = FakeDevice(
            filters = mutableMapOf(
                launcher.packageName to "speed",
                gsplit.packageName to "speed-profile"
            )
        )
        val repository = AotCompileRepository(device, catalog)
        val progress = mutableListOf<String>()

        val result = repository.compileOutdated { pkg, index, total ->
            progress += "${pkg.packageName}:$index/$total"
        }

        assertTrue(result.success)
        assertEquals(listOf(gsplit), result.compiled)
        assertEquals(listOf("com.salat.gsplit:1/1"), progress)
        assertEquals(listOf(gsplit.packageName), device.compiled)
        assertEquals(
            "cmd package compile -m speed -f 'com.salat.gsplit'",
            device.commands.single { it.startsWith("cmd package compile") }
        )
        val states = result.statuses.associate { it.pkg to it.state }
        assertEquals(AotCompileState.COMPILED, states[gsplit])
        assertEquals(AotCompileState.NOT_INSTALLED, states[terminal])
    }

    @Test
    fun nothingStaleRunsNoCompileCommand() = runBlocking {
        val device = FakeDevice(filters = mutableMapOf(launcher.packageName to "speed"))

        val result = AotCompileRepository(device, catalog).compileOutdated()

        assertTrue(result.success)
        assertTrue(result.requested.isEmpty())
        assertTrue(device.compiled.isEmpty())
    }

    @Test
    fun zeroExitWithoutSpeedFilterIsAFailure() = runBlocking {
        val device = FakeDevice(
            filters = mutableMapOf(gsplit.packageName to "verify"),
            compileApplies = false
        )

        val result = AotCompileRepository(device, catalog).compileOutdated()

        assertFalse(result.success)
        assertFalse(result.retryable)
        assertEquals(listOf(gsplit), result.failed)
    }

    @Test
    fun nonZeroCompileExitIsAFailureEvenWhenFilterChanged() = runBlocking {
        val device = FakeDevice(
            filters = mutableMapOf(gsplit.packageName to "verify"),
            compileExitCode = 1
        )

        val result = AotCompileRepository(device, catalog).compileOutdated()

        assertFalse(result.success)
        assertEquals(listOf(gsplit), result.failed)
    }

    @Test
    fun transportFailureStopsTheBatchAndIsRetryable() = runBlocking {
        val device = FakeDevice(
            filters = mutableMapOf(
                launcher.packageName to "quicken",
                gsplit.packageName to "verify"
            ),
            compileFailure = AdbCommandFailureKind.TIMEOUT
        )

        val result = AotCompileRepository(device, catalog).compileOutdated()

        assertFalse(result.success)
        assertTrue(result.retryable)
        assertEquals(1, device.commands.count { it.startsWith("cmd package compile") })
        assertEquals(listOf(launcher, gsplit), result.failed)
    }

    @Test
    fun failedStatusQueryCompilesNothingAndIsRetryable() = runBlocking {
        val device = FakeDevice(
            filters = mutableMapOf(gsplit.packageName to "verify"),
            statusFailure = AdbCommandFailureKind.CONNECT
        )

        val result = AotCompileRepository(device, catalog).compileOutdated()

        assertFalse(result.success)
        assertTrue(result.retryable)
        assertTrue(device.compiled.isEmpty())
    }

    @Test
    fun compilePackageForcesRecompileOfAlreadyCompiledPackage() = runBlocking {
        val device = FakeDevice(filters = mutableMapOf(launcher.packageName to "speed"))

        val result = AotCompileRepository(device, catalog).compilePackage(launcher)

        assertTrue(result.success)
        assertEquals(listOf(launcher.packageName), device.compiled)
    }

    @Test
    fun compilePackageSkipsMissingPackage() = runBlocking {
        val device = FakeDevice(filters = mutableMapOf())

        val result = AotCompileRepository(device, catalog).compilePackage(terminal)

        assertTrue(result.requested.isEmpty())
        assertTrue(device.compiled.isEmpty())
    }

    @Test
    fun aotAndCodecOperationsShareOneProcessWideLock() = runBlocking {
        val device = FakeDevice(
            filters = mutableMapOf(gsplit.packageName to "verify"),
            commandDelayMs = 20
        )
        val aot = AotCompileRepository(device, catalog)
        val codec = HevcCodecFixRepository(MinimalAssets(temporaryFolder.newFolder("assets")), device)

        listOf(
            async { aot.compileOutdated() },
            async { codec.detectCurrentVariant() },
            async { aot.queryStatus() }
        ).awaitAll()

        assertEquals(1, device.maxConcurrent.get())
    }

    @Test
    fun statusCommandQuotesPackagesAndFramesEachSection() {
        val command = AotCompileRepository.buildStatusCommand(listOf(launcher, gsplit))

        assertTrue(command.contains("for p in 'com.geely.atlaslauncher' 'com.salat.gsplit'; do"))
        assertTrue(command.contains("dumpsys package dexopt \"\$p\""))
        assertTrue(command.contains("${AotDexoptStatusParser.DUMP_EXIT_MARKER}\$?"))
        assertTrue(command.endsWith("echo ${AotDexoptStatusParser.END_MARKER}"))
        assertFalse(command.contains("su "))
    }

    /** Simulates the package manager: answers dexopt dumps and applies compile requests. */
    private class FakeDevice(
        private val filters: MutableMap<String, String>,
        private val compileApplies: Boolean = true,
        private val compileExitCode: Int = 0,
        private val compileFailure: AdbCommandFailureKind? = null,
        private val statusFailure: AdbCommandFailureKind? = null,
        private val commandDelayMs: Long = 0
    ) : AdbCommandExecutor {
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val compiled: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val maxConcurrent = AtomicInteger()
        private val active = AtomicInteger()

        override suspend fun execute(command: String, timeoutMs: Long): AdbCommandResult {
            val now = active.incrementAndGet()
            maxConcurrent.accumulateAndGet(now, ::maxOf)
            try {
                commands += command
                if (commandDelayMs > 0) delay(commandDelayMs)
                return when {
                    command.startsWith("cmd package compile") -> compile(command)
                    command.startsWith("for p in") -> status(command)
                    else -> AdbCommandResult(stdout = "variant:msmnile", exitCode = 0)
                }
            } finally {
                active.decrementAndGet()
            }
        }

        private fun compile(command: String): AdbCommandResult {
            compileFailure?.let { return AdbCommandResult.failure(it, "simulated $it") }
            val pkg = command.substringAfterLast(" ").trim('\'')
            compiled += pkg
            if (compileApplies) filters[pkg] = "speed"
            return AdbCommandResult(
                stdout = if (compileExitCode == 0) "Success" else "Failure",
                exitCode = compileExitCode
            )
        }

        private fun status(command: String): AdbCommandResult {
            statusFailure?.let { return AdbCommandResult.failure(it, "simulated $it") }
            val packages = Regex("'([^']+)'").findAll(command.substringBefore("; do"))
                .map { it.groupValues[1] }
                .toList()
            val output = buildString {
                packages.forEach { pkg ->
                    appendLine("${AotDexoptStatusParser.PACKAGE_MARKER}$pkg")
                    val filter = filters[pkg]
                    if (filter == null) {
                        appendLine("Unable to find package: $pkg")
                    } else {
                        appendLine("Dexopt state:")
                        appendLine("  [$pkg]")
                        appendLine("    path: /data/app/$pkg/base.apk")
                        appendLine("      arm64: [status=$filter] [reason=test]")
                    }
                    appendLine("${AotDexoptStatusParser.DUMP_EXIT_MARKER}0")
                }
                appendLine(AotDexoptStatusParser.END_MARKER)
            }
            return AdbCommandResult(stdout = output, exitCode = 0)
        }
    }

    private class MinimalAssets(private val root: File) : HevcAssetSource {
        override fun stage(variant: HevcCodecFixVariant): File = root

        override fun stageScript(assetPath: String): File =
            File(root, assetPath).apply { writeText("echo variant:msmnile") }

        override fun readText(assetPath: String): String = ""

        override fun getString(resource: Int, vararg arguments: Any): String = "text"
    }
}
