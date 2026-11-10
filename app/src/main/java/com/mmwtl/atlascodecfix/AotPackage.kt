package com.mmwtl.atlascodecfix

/** An application that the AOT tab can compile with the `speed` compiler filter. */
data class AotPackage(
    val packageName: String,
    val title: String
) {
    init {
        require(PACKAGE_NAME_PATTERN.matches(packageName)) { "Invalid package name: $packageName" }
    }

    private companion object {
        private val PACKAGE_NAME_PATTERN = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")
    }
}

object AotCatalog {
    /**
     * Fixed list of Atlas and companion applications. AtlasTools itself stays last, so a batch
     * finishes every other app before touching the running tool. `cmd package compile` does not
     * stop the running process; the new code is used from its next start.
     */
    val PACKAGES: List<AotPackage> = listOf(
        AotPackage("com.geely.atlaslauncher", "Atlas Launcher"),
        AotPackage("com.mmwtl.atlasappwidget", "Atlas App Widget"),
        AotPackage("com.mmwtl.atlasappwindow", "Atlas App Window"),
        AotPackage("com.mmwtl.atlasclimatewidget", "Atlas Climate Widget"),
        AotPackage("com.mmwtl.atlasmediawidget", "Atlas Media Widget"),
        AotPackage("com.mmwtl.atlasmediaapi", "Atlas Media API"),
        AotPackage("com.mmwtl.atlasdim", "Atlas Dim"),
        AotPackage("com.mmwtl.atlasterminal", "Atlas Terminal"),
        AotPackage("com.mmwtl.gibcompanion", "GIB Companion"),
        AotPackage("com.salat.gbinder", "GInputBridge (GIB)"),
        AotPackage("com.salat.gsplit", "GSplit"),
        AotPackage("com.andrerinas.headunitrevived", "Open Headunit"),
        AotPackage("com.andrerinas.headunitrevived.geely", "Open Headunit Geely"),
        AotPackage("com.geely.online.service", "GMediaProxy (GMP)"),
        AotPackage("com.salat.gmediahud", "GMediaHud (GMH)"),
        AotPackage(BuildConfig.APPLICATION_ID, "Atlas Tools")
    )
}

enum class AotCompileState {
    /** Every primary dex file is compiled with `speed` (or the stronger `everything`). */
    COMPILED,

    /** Installed, but at least one primary dex file uses a weaker filter. */
    NOT_COMPILED,

    /** The package manager does not know the package. */
    NOT_INSTALLED,

    /** The state could not be read. */
    UNKNOWN
}

data class AotPackageStatus(
    val pkg: AotPackage,
    val state: AotCompileState,
    val filters: List<String> = emptyList()
)

/**
 * Parses the output of the AOT status command: one full `dumpsys package dexopt` followed by
 * `aot_dump_exit:<n>` and `aot_end`. Android 11 ignores a package argument to `dexopt` and always
 * dumps every package, so the dump is read once and searched for each `[package]` block.
 */
object AotDexoptStatusParser {
    const val DUMP_EXIT_MARKER = "aot_dump_exit:"
    const val END_MARKER = "aot_end"

    private val COMPILED_FILTERS = setOf("speed", "everything")
    private val STATUS_LINE = Regex("""^\s*[A-Za-z0-9_]+:\s*\[status=([^\]]+)]""")
    private val PACKAGE_HEADER = Regex("""^\s*\[([^\]=\s]+)]\s*$""")

    fun hasEndMarker(output: String): Boolean =
        output.lineSequence().any { it.trim() == END_MARKER }

    fun parse(output: String, packages: List<AotPackage>): List<AotPackageStatus> {
        val lines = output.lines()
        val dumpExit = lines.firstNotNullOfOrNull { line ->
            line.trim().takeIf { it.startsWith(DUMP_EXIT_MARKER) }
                ?.removePrefix(DUMP_EXIT_MARKER)
                ?.trim()
                ?.toIntOrNull()
        }
        if (dumpExit != 0 || !hasEndMarker(output)) {
            return packages.map { AotPackageStatus(it, AotCompileState.UNKNOWN) }
        }
        val headers = HashMap<String, Int>()
        lines.forEachIndexed { index, line ->
            PACKAGE_HEADER.find(line)?.groupValues?.get(1)?.let { headers.putIfAbsent(it, index) }
        }
        return packages.map { pkg ->
            val headerIndex = headers[pkg.packageName]
                ?: return@map AotPackageStatus(pkg, AotCompileState.NOT_INSTALLED)
            parseBlock(pkg, lines, headerIndex)
        }
    }

    private fun parseBlock(pkg: AotPackage, lines: List<String>, headerIndex: Int): AotPackageStatus {
        val filters = mutableListOf<String>()
        for (line in lines.drop(headerIndex + 1)) {
            if (PACKAGE_HEADER.matches(line) || line.trim().startsWith(DUMP_EXIT_MARKER)) break
            // Secondary dex files are loaded by the app at runtime and are not compiled by
            // `cmd package compile`; counting them would make a package look permanently stale.
            if (line.contains("secondary dex", ignoreCase = true)) break
            STATUS_LINE.find(line)?.groupValues?.get(1)?.trim()?.let(filters::add)
        }
        val state = when {
            filters.isEmpty() -> AotCompileState.UNKNOWN
            filters.all { it in COMPILED_FILTERS } -> AotCompileState.COMPILED
            else -> AotCompileState.NOT_COMPILED
        }
        return AotPackageStatus(pkg, state, filters.distinct())
    }
}
