package com.mmwtl.atlascodecfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AotDexoptStatusParserTest {
    private val launcher = AotPackage("com.geely.atlaslauncher", "Launcher")
    private val headunit = AotPackage("com.andrerinas.headunitrevived", "Open Headunit")
    private val headunitGeely = AotPackage("com.andrerinas.headunitrevived.geely", "OH Geely")

    @Test
    fun android11SpeedOutputIsCompiled() {
        val output = section(
            launcher,
            """
            Dexopt state:
              [com.geely.atlaslauncher]
                path: /data/app/~~abc==/com.geely.atlaslauncher-xyz==/base.apk
                  arm64: [status=speed] [reason=cmdline]
            """
        ) + END

        val status = AotDexoptStatusParser.parse(output, listOf(launcher)).single()

        assertEquals(AotCompileState.COMPILED, status.state)
        assertEquals(listOf("speed"), status.filters)
    }

    @Test
    fun anyWeakerFilterAcrossSplitsOrAbisIsNotCompiled() {
        val output = section(
            headunit,
            """
            Dexopt state:
              [com.andrerinas.headunitrevived]
                path: /data/app/x/base.apk
                  arm64: [status=speed] [reason=cmdline]
                  arm: [status=speed-profile] [reason=bg-dexopt]
                path: /data/app/x/split_config.apk
                  arm64: [status=speed] [reason=cmdline]
            """
        ) + END

        val status = AotDexoptStatusParser.parse(output, listOf(headunit)).single()

        assertEquals(AotCompileState.NOT_COMPILED, status.state)
        assertEquals(listOf("speed", "speed-profile"), status.filters)
    }

    @Test
    fun artServiceOutputWithLocationLinesIsParsed() {
        val output = section(
            launcher,
            """
            [com.geely.atlaslauncher]
              path: /data/app/x/base.apk
                arm64: [status=everything] [reason=cmdline] [primary-abi]
                  [location is /data/app/x/oat/arm64/base.odex]
            """
        ) + END

        val status = AotDexoptStatusParser.parse(output, listOf(launcher)).single()

        assertEquals(AotCompileState.COMPILED, status.state)
    }

    @Test
    fun secondaryDexFilesDoNotMakeAPackageStale() {
        val output = section(
            launcher,
            """
            [com.geely.atlaslauncher]
              path: /data/app/x/base.apk
                arm64: [status=speed] [reason=cmdline]
              known secondary dex files:
                /data/user/0/com.geely.atlaslauncher/files/plugin.dex
                  arm64: [status=run-from-apk]
            """
        ) + END

        assertEquals(
            AotCompileState.COMPILED,
            AotDexoptStatusParser.parse(output, listOf(launcher)).single().state
        )
    }

    @Test
    fun prefixPackageNameDoesNotMatchLongerPackage() {
        // Only the Geely build is installed; the store build must not borrow its header.
        val output = section(headunit, "Unable to find package: com.andrerinas.headunitrevived") +
            section(
                headunitGeely,
                """
                [com.andrerinas.headunitrevived.geely]
                  path: /data/app/y/base.apk
                    arm64: [status=speed] [reason=cmdline]
                """
            ) + END

        val statuses = AotDexoptStatusParser.parse(output, listOf(headunit, headunitGeely))

        assertEquals(AotCompileState.NOT_INSTALLED, statuses[0].state)
        assertEquals(AotCompileState.COMPILED, statuses[1].state)
    }

    @Test
    fun unfilteredDumpUsesOnlyTheRequestedPackageBlock() {
        val output = section(
            launcher,
            """
            Dexopt state:
              [com.other.app]
                path: /data/app/o/base.apk
                  arm64: [status=verify] [reason=install]
              [com.geely.atlaslauncher]
                path: /data/app/x/base.apk
                  arm64: [status=speed] [reason=cmdline]
              [com.third.app]
                path: /data/app/t/base.apk
                  arm64: [status=quicken] [reason=install]
            """
        ) + END

        val status = AotDexoptStatusParser.parse(output, listOf(launcher)).single()

        assertEquals(AotCompileState.COMPILED, status.state)
        assertEquals(listOf("speed"), status.filters)
    }

    @Test
    fun failedDumpOrMissingSectionIsUnknownNotInstalledOrCompiled() {
        val failedDump = "Can't find service: package\n" +
            "${AotDexoptStatusParser.DUMP_EXIT_MARKER}255\n${AotDexoptStatusParser.END_MARKER}\n"

        assertEquals(
            AotCompileState.UNKNOWN,
            AotDexoptStatusParser.parse(failedDump, listOf(launcher)).single().state
        )
        assertEquals(
            AotCompileState.UNKNOWN,
            AotDexoptStatusParser.parse("Dexopt state:\n", listOf(launcher)).single().state
        )
    }

    @Test
    fun headerWithoutStatusLinesIsUnknown() {
        val output = section(launcher, "[com.geely.atlaslauncher]\n  path: /data/app/x/base.apk") + END

        assertEquals(
            AotCompileState.UNKNOWN,
            AotDexoptStatusParser.parse(output, listOf(launcher)).single().state
        )
    }

    @Test
    fun endMarkerMustBeOnItsOwnLine() {
        assertTrue(AotDexoptStatusParser.hasEndMarker("x\naot_end\n"))
        assertFalse(AotDexoptStatusParser.hasEndMarker("for p in a; do echo aot_end; done"))
    }

    @Test
    fun catalogContainsOnlyAtlasAppsWithoutDuplicatesAndSelfLast() {
        val names = AotCatalog.PACKAGES.map(AotPackage::packageName)

        assertEquals(names.size, names.toSet().size)
        assertTrue(names.contains("com.geely.atlaslauncher"))
        assertTrue(
            names.all { it == "com.geely.atlaslauncher" || it.startsWith("com.mmwtl.atlas") }
        )
        assertTrue(AotCatalog.FORMER_PACKAGES.none(AotCatalog::isCatalogPackage))
        assertEquals("com.mmwtl.atlascodecfix", names.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun packageNamesWithShellMetacharactersAreRejected() {
        AotPackage("com.example;reboot", "bad")
    }

    @Test
    fun android11EmulatorFullDumpIsParsed() {
        // Trimmed from a real Android 11 `dumpsys package dexopt`, which ignores a package filter.
        val output = """
            Dexopt state:
              [com.google.android.youtube]
                path: /product/app/YouTube/YouTube.apk
                  arm64: [status=quicken] [reason=prebuilt]
                  known secondary dex files:
                    /data/user/0/com.google.android.youtube/cache/1588009193101.jar
                      class loader context: PCL[];PCL[/product/app/YouTube/YouTube.apk]
              [com.android.cts.priv.ctsshim]
              [com.mmwtl.atlasclimatewidget]
                path: /data/app/~~MMdB==/com.mmwtl.atlasclimatewidget-GPvE==/base.apk
                  arm64: [status=verify] [reason=boot]
              [com.geely.atlaslauncher]
                path: /data/app/~~pymp==/com.geely.atlaslauncher-c2Yf==/base.apk
                  arm64: [status=speed] [reason=cmdline]
                  used by other apps: [com.mmwtl.atlasappwidget]
        """.trimIndent() + "\n" + END
        val climate = AotPackage("com.mmwtl.atlasclimatewidget", "Climate")
        val shim = AotPackage("com.android.cts.priv.ctsshim", "Shim")

        val statuses = AotDexoptStatusParser.parse(output, listOf(climate, launcher, shim, headunit))

        assertEquals(
            listOf(
                AotCompileState.NOT_COMPILED,
                AotCompileState.COMPILED,
                AotCompileState.UNKNOWN,
                AotCompileState.NOT_INSTALLED
            ),
            statuses.map(AotPackageStatus::state)
        )
        assertEquals(listOf("verify"), statuses[0].filters)
    }

    private fun section(@Suppress("UNUSED_PARAMETER") pkg: AotPackage, body: String): String =
        body.trimIndent() + "\n"

    private companion object {
        val END = "${AotDexoptStatusParser.DUMP_EXIT_MARKER}0\n${AotDexoptStatusParser.END_MARKER}\n"
    }
}
