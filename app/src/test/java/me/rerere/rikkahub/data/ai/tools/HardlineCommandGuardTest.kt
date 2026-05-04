package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests proving HardlineCommandGuard catches every pattern it claims to, AND doesn't
 * false-positive on harmless commands that mention dangerous words ("which shutdown",
 * "echo reboot", "grep mkfs in logs"). These are the patterns Always-Allow CANNOT override.
 */
class HardlineCommandGuardTest {

    // -----------------------------------------------------------------------
    // Positive: hardline patterns MUST block
    // -----------------------------------------------------------------------

    @Test fun `rm -rf root filesystem`() = assertBlocked("rm -rf /")

    @Test fun `rm -rf root star`() = assertBlocked("rm -rf /*")

    @Test fun `rm -rf system dirs`() {
        for (dir in listOf("/home", "/root", "/etc", "/usr", "/var", "/bin", "/sbin", "/boot", "/lib")) {
            assertBlocked("rm -rf $dir")
            assertBlocked("rm -rf $dir/*")
        }
    }

    @Test fun `rm -rf home dir via tilde or var`() {
        assertBlocked("rm -rf ~")
        assertBlocked("rm -rf ~/")
        assertBlocked("rm -rf \$HOME")
    }

    @Test fun `mkfs variants`() {
        assertBlocked("mkfs /dev/sdb1")
        assertBlocked("mkfs.ext4 /dev/sdb1")
        assertBlocked("mkfs.ext4 /dev/loop0")
        assertBlocked("mkfs.btrfs /dev/sda1")
    }

    @Test fun `dd to raw block device`() {
        assertBlocked("dd if=/dev/zero of=/dev/sda")
        assertBlocked("dd if=/dev/zero of=/dev/nvme0n1")
        assertBlocked("dd if=image.iso of=/dev/sdb bs=4M")
    }

    @Test fun `redirect to raw block device`() {
        assertBlocked("cat junk > /dev/sda")
        assertBlocked("echo wipe > /dev/nvme0n1")
    }

    @Test fun `fork bomb`() = assertBlocked(":(){ :|:& };:")

    @Test fun `kill all processes`() {
        assertBlocked("kill -1")
        assertBlocked("kill -9 -1")
    }

    @Test fun `shutdown reboot halt poweroff at command position`() {
        for (cmd in listOf("shutdown", "shutdown -h now", "reboot", "halt", "poweroff")) {
            assertBlocked(cmd)
            assertBlocked("sudo $cmd")
            assertBlocked("nohup $cmd")
            // After a shell separator
            assertBlocked("echo bye; $cmd")
            assertBlocked("touch /tmp/x && $cmd")
        }
    }

    @Test fun `init 0 and init 6`() {
        assertBlocked("init 0")
        assertBlocked("init 6")
    }

    @Test fun `systemctl poweroff variants`() {
        for (op in listOf("poweroff", "reboot", "halt", "kexec")) {
            assertBlocked("systemctl $op")
        }
    }

    @Test fun `telinit shutdown reboot`() {
        assertBlocked("telinit 0")
        assertBlocked("telinit 6")
    }

    // -----------------------------------------------------------------------
    // Negative: harmless commands that MENTION dangerous words MUST NOT block
    // -----------------------------------------------------------------------

    @Test fun `which shutdown is not blocked`() {
        // The model's pre-flight existence check that tripped up our earlier test —
        // 'shutdown' is an arg to `which`, not at command position.
        assertAllowed("which shutdown")
        assertAllowed("which shutdown || echo not found")
    }

    @Test fun `echo or grep mentioning danger words is not blocked`() {
        assertAllowed("echo reboot")
        assertAllowed("echo 'shutdown -h now'")
        assertAllowed("grep mkfs /var/log/syslog")
        assertAllowed("man rm")
        assertAllowed("type shutdown")
    }

    @Test fun `rm of safe paths is not blocked`() {
        assertAllowed("rm /tmp/foo.txt")
        assertAllowed("rm -rf /tmp/scratch")
        assertAllowed("rm -rf ~/Downloads/throwaway")
        assertAllowed("rm -rf ./build")
    }

    @Test fun `dd to file is not blocked`() {
        assertAllowed("dd if=/dev/zero of=/tmp/zerofile bs=1M count=10")
        assertAllowed("dd if=image.iso of=/sdcard/image.iso")
    }

    @Test fun `kill of specific pid is not blocked`() {
        assertAllowed("kill 1234")
        assertAllowed("kill -9 1234")
        assertAllowed("kill -TERM 5678")
    }

    @Test fun `systemctl restart of a service is not blocked`() {
        // restart/stop of a named service is a normal admin op, not the unconditional
        // power-off list — it stays in the regular dangerous-but-approvable tier.
        assertAllowed("systemctl restart nginx")
        assertAllowed("systemctl stop apache2")
    }

    // -----------------------------------------------------------------------
    // Tool-aware entrypoint: termux command/exe + ssh
    // -----------------------------------------------------------------------

    @Test fun `termux command form blocks rm -rf root`() {
        val reason = HardlineCommandGuard.checkTool(
            "termux_run_command",
            """{"command":"rm -rf /"}"""
        )
        assertNotNull("expected hardline match", reason)
    }

    @Test fun `termux executable+arguments form blocks shutdown`() {
        val reason = HardlineCommandGuard.checkTool(
            "termux_run_command",
            """{"executable":"/usr/sbin/shutdown","arguments":["-h","now"]}"""
        )
        assertNotNull("expected hardline match in executable form", reason)
    }

    @Test fun `ssh_exec blocks rm -rf root`() {
        val reason = HardlineCommandGuard.checkTool(
            "ssh_exec",
            """{"command":"rm -rf /"}"""
        )
        assertNotNull("expected hardline match on ssh_exec", reason)
    }

    @Test fun `ssh_exec_saved blocks mkfs`() {
        val reason = HardlineCommandGuard.checkTool(
            "ssh_exec_saved",
            """{"name":"box","command":"mkfs.ext4 /dev/sdb1"}"""
        )
        assertNotNull("expected hardline match on ssh_exec_saved", reason)
    }

    @Test fun `safe ssh command is allowed`() {
        val reason = HardlineCommandGuard.checkTool(
            "ssh_exec",
            """{"command":"uptime"}"""
        )
        assertNull("safe command should not match", reason)
    }

    @Test fun `tool we don't gate returns null`() {
        // get_battery_status and friends aren't shell-content tools — guard returns null.
        val reason = HardlineCommandGuard.checkTool(
            "get_battery_status",
            """{}"""
        )
        assertNull("non-shell tool should not be checked", reason)
    }

    @Test fun `malformed JSON returns null instead of throwing`() {
        val reason = HardlineCommandGuard.checkTool("termux_run_command", "{not json")
        assertNull("malformed input should not crash", reason)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun assertBlocked(cmd: String) {
        val reason = HardlineCommandGuard.checkCommand(cmd)
        assertNotNull("expected '$cmd' to match a hardline pattern", reason)
    }

    private fun assertAllowed(cmd: String) {
        val reason = HardlineCommandGuard.checkCommand(cmd)
        assertEquals(
            "expected '$cmd' NOT to match any hardline pattern, but matched: $reason",
            null,
            reason
        )
    }
}
