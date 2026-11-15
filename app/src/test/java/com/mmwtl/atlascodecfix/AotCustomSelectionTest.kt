package com.mmwtl.atlascodecfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AotCustomSelectionTest {
    private val self = BuildConfig.APPLICATION_ID

    @Test
    fun customPackagesFollowTheCatalogSortedAndAtlasToolsStaysLast() {
        val list = AotCatalog.withCustom(
            listOf(
                AotPackage("org.videolan.vlc", "VLC"),
                AotPackage("com.yandex.navi", "Navigator"),
                AotPackage("com.geely.atlaslauncher", "Duplicate of catalog"),
                AotPackage("org.videolan.vlc", "VLC again")
            )
        )

        val catalog = AotCatalog.PACKAGES
        assertEquals(catalog.dropLast(1), list.take(catalog.size - 1))
        assertEquals(
            listOf("com.yandex.navi", "org.videolan.vlc", self),
            list.drop(catalog.size - 1).map(AotPackage::packageName)
        )
        assertEquals(list.size, list.map(AotPackage::packageName).toSet().size)
    }

    @Test
    fun emptySelectionKeepsTheCatalogUnchanged() {
        assertEquals(AotCatalog.PACKAGES, AotCatalog.withCustom(emptyList()))
    }

    @Test
    fun pickerHidesCatalogAndSystemAppsAndListsSelectedFirst() {
        val installed = listOf(
            InstalledApp("org.videolan.vlc", "VLC", isSystem = false),
            InstalledApp("com.android.settings", "Settings", isSystem = true),
            InstalledApp("com.yandex.navi", "Navigator", isSystem = false),
            InstalledApp("com.geely.atlaslauncher", "Atlas Launcher", isSystem = false),
            InstalledApp("com.android.phone", "Phone", isSystem = true)
        )

        val entries = AotAppPicker.entries(
            installed,
            selected = setOf("org.videolan.vlc", "com.android.phone"),
            showSystem = false,
            query = ""
        )

        assertEquals(
            listOf("com.android.phone", "org.videolan.vlc", "com.yandex.navi"),
            entries.map(InstalledApp::packageName)
        )
    }

    @Test
    fun pickerShowsSystemAppsOnRequestAndFiltersByLabelOrPackage() {
        val installed = listOf(
            InstalledApp("org.videolan.vlc", "VLC", isSystem = false),
            InstalledApp("com.android.settings", "Settings", isSystem = true)
        )

        assertEquals(
            listOf("com.android.settings", "org.videolan.vlc"),
            AotAppPicker.entries(installed, emptySet(), showSystem = true, query = "")
                .map(InstalledApp::packageName)
        )
        assertEquals(
            listOf("com.android.settings"),
            AotAppPicker.entries(installed, emptySet(), showSystem = true, query = " sett ")
                .map(InstalledApp::packageName)
        )
        assertEquals(
            listOf("org.videolan.vlc"),
            AotAppPicker.entries(installed, emptySet(), showSystem = true, query = "VIDEOLAN")
                .map(InstalledApp::packageName)
        )
    }

    @Test
    fun selectedButUninstalledAppStaysVisibleSoItCanBeRemoved() {
        val entries = AotAppPicker.entries(
            installed = emptyList(),
            selected = setOf("com.removed.app"),
            showSystem = false,
            query = ""
        )

        assertEquals(listOf(InstalledApp("com.removed.app", "com.removed.app", false)), entries)
    }

    @Test
    fun migrationAddsOnlyInstalledFormerCatalogAppsAndKeepsTheSelection() {
        val installed = setOf("com.salat.gsplit", "com.geely.online.service")

        val migrated = AotCatalog.migrateSelection(setOf("org.videolan.vlc")) { name ->
            if (name == "com.salat.gmediahud") error("package manager unavailable")
            name in installed
        }

        assertEquals(setOf("org.videolan.vlc", "com.salat.gsplit", "com.geely.online.service"), migrated)
    }

    @Test
    fun packageNameValidationRejectsShellMetacharacters() {
        assertTrue(AotPackage.isValidPackageName("org.videolan.vlc"))
        assertFalse(AotPackage.isValidPackageName("org.vlc; reboot"))
        assertFalse(AotPackage.isValidPackageName("single"))
        assertTrue(AotCatalog.isCatalogPackage(self))
    }
}
