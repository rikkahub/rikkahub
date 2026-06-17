package me.rerere.rikkahub.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for SkillUrlImporter — URL guard + format detection + tool-name
 * transcoder. The actual install-from-text round-trip needs SkillManager + filesystem and
 * is exercised end-to-end via instrumented testing on device.
 */
class SkillUrlImporterTest {

    private val importer = SkillUrlImporter(skillManager = TempDirSkillSaver())

    // -- URL guard -----------------------------------------------------------------------

    @Test fun `blank url rejected`() {
        val r = importer.checkUrl("")!!
        assertEquals("invalid_url", r.first)
    }

    @Test fun `non-http schemes rejected`() {
        for (bad in listOf(
            "file:///etc/passwd",
            "ftp://example.com/skill.md",
            "data:text/plain,hi",
            "javascript:alert(1)",
            "ssh://user@server/path",
        )) {
            val r = importer.checkUrl(bad)
            assertNotNull("expected reject for $bad", r)
            assertEquals("unsupported_url_scheme", r!!.first)
        }
    }

    @Test fun `http and https accepted`() {
        assertNull(importer.checkUrl("https://example.com/skill.md"))
        assertNull(importer.checkUrl("http://example.com/skill.md"))
    }

    @Test fun `loopback hosts rejected`() {
        for (bad in listOf(
            "http://localhost/skill.md",
            "http://localhost.:8080/skill.md",
            "http://127.0.0.1/skill.md",
            "http://127.42.99.1/skill.md",
            "http://[::1]/skill.md",
            "http://0.0.0.0/skill.md",
        )) {
            val r = importer.checkUrl(bad)
            assertNotNull("expected loopback reject for $bad", r)
            assertEquals("loopback_host_rejected", r!!.first)
        }
    }

    @Test fun `isLoopback covers IPv4-mapped IPv6 and bracketed long-form`() {
        assertTrue(importer.isLoopback("localhost"))
        assertTrue(importer.isLoopback("LOCALHOST"))
        assertTrue(importer.isLoopback("0.0.0.0"))
        assertTrue(importer.isLoopback("[0:0:0:0:0:0:0:1]"))
    }

    @Test fun `isLoopback does not over-match`() {
        for (host in listOf("example.com", "127.example.com", "192.168.1.1", "10.0.0.1")) {
            assertFalse("expected non-loopback for $host", importer.isLoopback(host))
        }
    }

    // -- Format detection (via installFromText path) -------------------------------------

    @Test fun `native format installs cleanly with frontmatter intact`() {
        val md = """
            ---
            name: my-skill
            description: A simple test skill.
            ---

            # My Skill

            Hello.
        """.trimIndent()
        val r = importer.importFromText(md, sourceLabel = "test://native")
        assertTrue("expected Ok, got $r", r is SkillUrlImporter.Result.Ok)
        val ok = r as SkillUrlImporter.Result.Ok
        assertEquals(SkillUrlImporter.SkillFormat.NATIVE, ok.format)
        assertEquals("my-skill", ok.metadata.name)
    }

    @Test fun `openclaw format synthesises frontmatter from H1`() {
        val md = """
            # Morning Routine

            Run my morning checks: battery, calendar, mail.

            ## Steps
            1. Read battery.
            2. Open calendar.
        """.trimIndent()
        val r = importer.importFromText(md, sourceLabel = "test://openclaw")
        assertTrue("expected Ok, got $r", r is SkillUrlImporter.Result.Ok)
        val ok = r as SkillUrlImporter.Result.Ok
        assertEquals(SkillUrlImporter.SkillFormat.OPENCLAW, ok.format)
        assertEquals("morning-routine", ok.metadata.name)
    }

    @Test fun `hermes JSON format produces native markdown`() {
        val json = """
            {
              "name": "Welcome Home",
              "description": "Run when I get home",
              "prompt": "Greet the user warmly.",
              "steps": ["Turn on lights", "Play music", "Set thermostat"]
            }
        """.trimIndent()
        val r = importer.importFromText(json, sourceLabel = "test://hermes")
        assertTrue("expected Ok, got $r", r is SkillUrlImporter.Result.Ok)
        val ok = r as SkillUrlImporter.Result.Ok
        assertEquals(SkillUrlImporter.SkillFormat.HERMES, ok.format)
        assertEquals("welcome-home", ok.metadata.name)
    }

    @Test fun `over-cap body rejected`() {
        val huge = "x".repeat(SkillUrlImporter.MAX_BODY_BYTES + 1)
        val r = importer.importFromText(huge, sourceLabel = null)
        assertTrue(r is SkillUrlImporter.Result.Err)
        assertEquals("body_too_large", (r as SkillUrlImporter.Result.Err).code)
    }

    @Test fun `empty body rejected`() {
        val r = importer.importFromText("", sourceLabel = null)
        assertTrue(r is SkillUrlImporter.Result.Err)
        assertEquals("empty_body", (r as SkillUrlImporter.Result.Err).code)
    }

    @Test fun `malformed Hermes JSON rejected`() {
        val r = importer.importFromText("{not json", sourceLabel = null)
        assertTrue("expected Err, got $r", r is SkillUrlImporter.Result.Err)
    }

    @Test fun `override name takes precedence`() {
        val md = """
            ---
            name: original
            description: x
            ---
            body
        """.trimIndent()
        val r = importer.importFromText(md, sourceLabel = null, overrideName = "renamed")
        assertTrue(r is SkillUrlImporter.Result.Ok)
        assertEquals("renamed", (r as SkillUrlImporter.Result.Ok).metadata.name)
    }

    @Test fun `native skill body with tool words in prose is stored verbatim`() {
        // A native skill (has frontmatter `name:`) must never be run through the transcoder.
        // Its prose mentions Read / Edit / Bash as ordinary words and must survive untouched.
        val saver = TempDirSkillSaver()
        val localImporter = SkillUrlImporter(skillManager = saver)
        val md = """
            ---
            name: verbatim-skill
            description: x
            ---

            First Read the docs, then Edit the file, then run Bash.
        """.trimIndent()
        val r = localImporter.importFromText(md, sourceLabel = "test://native")
        assertTrue("expected Ok, got $r", r is SkillUrlImporter.Result.Ok)
        assertEquals(SkillUrlImporter.SkillFormat.NATIVE,
            (r as SkillUrlImporter.Result.Ok).format)
        val written = saver.tmpDir.resolve("verbatim-skill/SKILL.md").readText()
        assertTrue("prose must be preserved, got: $written",
            written.contains("First Read the docs, then Edit the file, then run Bash."))
        assertFalse(written.contains("read_file"))
        assertFalse(written.contains("write_text_file"))
    }

    @Test fun `unclassifiable markdown without name is not silently transcoded`() {
        // Plain markdown with no frontmatter name and no openclaw marker used to default to
        // OPENCLAW (synthesising a name from the H1 and transcoding the body). It must now be
        // treated as NATIVE and surface an honest missing_name error.
        val md = """
            # Some Notes

            Then Read the config and Edit it.
        """.trimIndent()
        val r = importer.importFromText(md, sourceLabel = null)
        assertTrue("expected Err, got $r", r is SkillUrlImporter.Result.Err)
        assertEquals("missing_name", (r as SkillUrlImporter.Result.Err).code)
    }

    @Test fun `invalid skill name rejected`() {
        val md = """
            ---
            name: BAD NAME WITH SPACES!
            description: x
            ---
            body
        """.trimIndent()
        val r = importer.importFromText(md, sourceLabel = null)
        assertTrue(r is SkillUrlImporter.Result.Err)
        assertEquals("invalid_name", (r as SkillUrlImporter.Result.Err).code)
    }
}

class ToolNameTranscoderTest {

    @Test fun `tool names mapped inside inline code spans`() {
        // Tool references in a skill body live in code spans, not prose. Only those map.
        val input = "First call `Bash` to install. Then `Read` the config. Then `Notify` the user."
        val out = ToolNameTranscoder.transcode(input)
        assertTrue(out.contains("termux_run_command"))
        assertTrue(out.contains("read_file"))
        assertTrue(out.contains("post_notification"))
        assertFalse(out.contains("`Bash`"))
    }

    @Test fun `tool names mapped inside fenced code blocks`() {
        val input = "```\nRead the file then Edit it with Write.\n```"
        val out = ToolNameTranscoder.transcode(input)
        assertTrue(out.contains("read_file"))
        assertTrue(out.contains("write_text_file"))
        assertFalse(out.contains("Read "))
    }

    @Test fun `prose outside code regions is left untouched`() {
        // Regression: the transcoder used to rewrite tool names everywhere, corrupting
        // ordinary sentences that happen to use the English words Read / Edit / Bash. Prose
        // must survive import verbatim — only code regions are eligible for substitution.
        val input = "Then Read the config and Edit it, then run Bash and Notify the user."
        val out = ToolNameTranscoder.transcode(input)
        assertEquals(input, out)
    }

    @Test fun `mixed prose and code maps only the code`() {
        val input = "Read the docs, then run `Read` on the path."
        val out = ToolNameTranscoder.transcode(input)
        // Prose "Read the docs" preserved; only the `Read` span is mapped.
        assertTrue(out.startsWith("Read the docs"))
        assertTrue(out.contains("read_file"))
        assertFalse(out.contains("`Read`"))
    }

    @Test fun `word-bounded — partial matches in code not rewritten`() {
        val input = "`BashScripts` and `ReadOnly` and `notification_action_click` are unaffected."
        val out = ToolNameTranscoder.transcode(input)
        assertEquals(input, out)
    }

    @Test fun `Hermes-style names mapped inside code spans`() {
        val input = "Use `send_message` to ping mom. Use `open_app` to launch."
        val out = ToolNameTranscoder.transcode(input)
        assertTrue(out.contains("telegram_send_message"))
        assertTrue(out.contains("launch_app"))
    }
}
