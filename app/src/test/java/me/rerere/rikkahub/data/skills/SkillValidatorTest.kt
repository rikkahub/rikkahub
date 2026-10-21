package me.rerere.rikkahub.data.skills

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillValidatorTest {
    private fun skill(name: String, description: String = "test") =
        "---\nname: $name\ndescription: $description\n---\n\nbody"

    private fun assertInvalid(content: String, expected: SkillValidationError) {
        val error = SkillValidator.validate(content).exceptionOrNull() as? SkillValidationException
        assertEquals(expected, error?.error)
    }

    @Test
    fun `valid skill returns its name`() {
        assertEquals("my-skill", SkillValidator.validate(skill("my-skill")).getOrNull())
        assertEquals("中文技能", SkillValidator.validate(skill("中文技能")).getOrNull())
    }

    @Test
    fun `missing name or description is rejected`() {
        assertInvalid("---\ndescription: test\n---\n", SkillValidationError.MissingName)
        assertInvalid("---\nname: my-skill\n---\n", SkillValidationError.MissingDescription)
        assertInvalid(skill("\"  \""), SkillValidationError.MissingName)
        assertInvalid(skill("my-skill", description = "\"\""), SkillValidationError.MissingDescription)
        assertInvalid("no frontmatter", SkillValidationError.MissingName)
    }

    @Test
    fun `non-string name is rejected`() {
        assertInvalid(skill("123"), SkillValidationError.MissingName)
    }

    @Test
    fun `name that cannot be used as directory name is rejected`() {
        assertInvalid(skill("\" my-skill \""), SkillValidationError.NameHasSurroundingWhitespace)
        assertInvalid(skill(".hidden"), SkillValidationError.NameStartsWithDot)
        assertInvalid(skill(".."), SkillValidationError.NameStartsWithDot)
        assertInvalid(skill("a/b"), SkillValidationError.NameHasInvalidChars)
        assertInvalid(skill("\"a\\\\b\""), SkillValidationError.NameHasInvalidChars)
        assertInvalid(skill("\"a\\nb\""), SkillValidationError.NameHasInvalidChars)
        assertInvalid(skill("a".repeat(SkillValidator.MAX_NAME_LENGTH + 1)), SkillValidationError.NameTooLong)
    }
}
