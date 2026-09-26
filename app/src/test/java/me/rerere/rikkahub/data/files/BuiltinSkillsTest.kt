package me.rerere.rikkahub.data.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltinSkillsTest {
    @Test
    fun `local skill overrides builtin skill with same name`() {
        val local = SkillMetadata(name = "shared", description = "local", skillDir = File("skills/shared"))
        val merged = mergeWithBuiltinSkills(
            local = listOf(local),
            builtin = listOf(
                SkillMetadata(name = "shared", description = "builtin", skillDir = File("builtin/shared"), builtin = true),
                SkillMetadata(name = "only-builtin", description = "builtin", skillDir = File("builtin/only"), builtin = true),
            ),
        )

        assertEquals(listOf("shared", "only-builtin"), merged.map { it.name })
        assertEquals(local, merged.first())
    }
}
