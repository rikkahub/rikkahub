package me.rerere.sandbox

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SandboxCommandBuilderTest {
    private val rootfs = File("build/test-rootfs").apply { mkdirs() }
    private val binaries = SandboxBinaries(
        proot = File("/native/libproot.so"),
        prootUserland = File("/native/libproot-userland.so"),
        loader = File("/native/libproot-loader.so"),
        loader32 = File("/native/libproot-loader32.so"),
    )

    @Test
    fun `should build default proot command`() {
        val command = SandboxCommandBuilder.build(
            binaries = binaries,
            config = SandboxConfig(rootfsDir = rootfs),
            guestCommand = listOf("/bin/sh", "-lc", "echo hello"),
        )

        assertEquals("/native/libproot-userland.so", command.first())
        assertTrue(command.contains("--kill-on-exit"))
        assertTrue(command.contains("-0"))
        assertTrue(command.contains(rootfs.absolutePath))
        assertTrue(command.contains("/usr/bin/env"))
        assertTrue(command.contains("HOME=/root"))
        assertTrue(command.contains("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"))
        assertEquals(listOf("/bin/sh", "-lc", "echo hello"), command.takeLast(3))
    }

    @Test
    fun `should include custom binds and custom env`() {
        val hostDir = File("build/test-workspace").apply { mkdirs() }
        val command = SandboxCommandBuilder.build(
            binaries = binaries,
            config = SandboxConfig(
                rootfsDir = rootfs,
                bindMounts = listOf(
                    SandboxBind(hostDir, "/workspace"),
                    SandboxBind("/tmp", "/guest-tmp", dereferenceGuestPath = false),
                ),
                guestEnvironment = mapOf("FOO" to "bar"),
                useDefaultBindings = false,
                clearGuestEnvironment = false,
                fakeRoot = false,
                useUserlandBinary = false,
            ),
            guestCommand = listOf("/usr/bin/env"),
        )

        assertEquals("/native/libproot.so", command.first())
        assertFalse(command.contains("-0"))
        assertFalse(command.contains("-i"))
        assertTrue(command.contains(hostDir.absolutePath + ":/workspace"))
        assertTrue(command.contains("/tmp:/guest-tmp!"))
        assertTrue(command.contains("FOO=bar"))
        assertTrue(command.contains("USER=sandbox"))
    }

    @Test
    fun `should filter missing default bind paths`() {
        val binds = SandboxCommandBuilder.resolvedBindings(
            SandboxConfig(
                rootfsDir = rootfs,
                useDefaultBindings = true,
            )
        )

        assertTrue(binds.none { it.hostPath.isBlank() })
    }
}
