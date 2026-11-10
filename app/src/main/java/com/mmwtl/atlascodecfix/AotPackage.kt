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
 * Parses the machine-framed output of the AOT status command. Each package section starts with
 * `aot_pkg:<name>`, holds `dumpsys package dexopt <name>` output and ends with `aot_dump_exit:<n>`.
 */
object AotDexoptStatusParser {
    const val PACKAGE_MARKER = "aot_pkg:"
    const val DUMP_EXIT_MARKER = "aot_dump_exit:"
    const val END_MARKER = "aot_end"

    private val COMPILED_FILTERS = setOf("speed", "everything")
    private val STATUS_LINE = Regex("""^\s*[A-Za-z0-9_]+:\s*\[status=([^\]]+)]""")
    private val PACKAGE_HEADER = Regex("""^\s*\[([^\]=\s]+)]\s*$""")

    fun hasEndMarker(output: String): Boolean =
        output.lineSequence().any { it.trim() == END_MARKER }

    fun parse(output: String, packages: List<AotPackage>): List<AotPackageStatus> {
        val sections = splitSections(output)
        return packages.map { pkg ->
            val section = sections[pkg.packageName]
                ?: return@map AotPackageStatus(pkg, AotCompileState.UNKNOWN)
            parseSection(pkg, section)
        }
    }

    private fun splitSections(output: String): Map<String, List<String>> {
        val sections = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith(PACKAGE_MARKER) -> {
                    current = mutableListOf<String>().also {
                        sections[trimmed.removePrefix(PACKAGE_MARKER).trim()] = it
                    }
                }
                trimmed == END_MARKER -> current = null
                else -> current?.add(line)
            }
        }
        return sections
    }

    private fun parseSection(pkg: AotPackage, lines: List<String>): AotPackageStatus {
        val dumpExit = lines.firstNotNullOfOrNull { line ->
            line.trim().takeIf { it.startsWith(DUMP_EXIT_MARKER) }
                ?.removePrefix(DUMP_EXIT_MARKER)
                ?.trim()
                ?.toIntOrNull()
        }
        if (dumpExit != 0) return AotPackageStatus(pkg, AotCompileState.UNKNOWN)

        val headerIndex = lines.indexOfFirst { line ->
            PACKAGE_HEADER.find(line)?.groupValues?.get(1) == pkg.packageName
        }
        if (headerIndex < 0) return AotPackageStatus(pkg, AotCompileState.NOT_INSTALLED)

        val filters = mutableListOf<String>()
        for (line in lines.drop(headerIndex + 1)) {
            if (PACKAGE_HEADER.matches(line)) break
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
