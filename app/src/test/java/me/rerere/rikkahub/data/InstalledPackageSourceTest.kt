package me.rerere.rikkahub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure shaping for the automation scope picker: [filterInstalledPackages] is the JVM-testable list
 * shape the picker renders (the Android enumeration around it needs a real PackageManager). Pins the
 * system filter, de-dup, and case-insensitive label sort.
 */
class InstalledPackageSourceTest {

    private fun pkg(name: String, label: String, system: Boolean) =
        InstalledPackageInfo(packageName = name, label = label, isSystem = system)

    @Test
    fun `includeSystem=false drops system packages`() {
        val out = sequenceOf(
            pkg("com.user.app", "User App", system = false),
            pkg("com.android.systemui", "System UI", system = true),
        ).filterInstalledPackages(includeSystem = false)

        assertEquals(listOf("com.user.app"), out.map { it.packageName })
        assertFalse("a system package leaked with includeSystem=false", out.any { it.isSystem })
    }

    @Test
    fun `includeSystem=true keeps system packages`() {
        val out = sequenceOf(
            pkg("com.user.app", "User App", system = false),
            pkg("com.android.systemui", "System UI", system = true),
        ).filterInstalledPackages(includeSystem = true)

        assertTrue("system packages must be present with includeSystem=true", out.any { it.isSystem })
        assertEquals(2, out.size)
    }

    @Test
    fun `entries are de-duplicated by package name`() {
        val out = sequenceOf(
            pkg("com.dup.app", "Alpha", system = false),
            pkg("com.dup.app", "Alpha (alt loader)", system = false),
        ).filterInstalledPackages(includeSystem = true)

        assertEquals(1, out.size)
    }

    @Test
    fun `sorted case-insensitively by label then package`() {
        val out = sequenceOf(
            pkg("com.b", "banana", system = false),
            pkg("com.a", "Apple", system = false),
            pkg("com.c", "apple", system = false), // same label as com.a, case-insensitive — package breaks the tie
        ).filterInstalledPackages(includeSystem = true)

        assertEquals(listOf("com.a", "com.c", "com.b"), out.map { it.packageName })
    }
}
