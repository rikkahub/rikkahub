package me.rerere.ai.provider

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * CI guard for #242: ProviderSetting is a pure serializable domain model with no presentation
 * glue. These assertions FAIL on the pre-fix model (where `description`/`shortDescription`
 * presentation members existed) and PASS once the Compose leak is removed — pinning the invariant
 * in code, complementing the CI grep gate that scans ai/src for UI-runtime imports.
 *
 * Uses plain JVM reflection (no kotlin-reflect on the :ai test classpath) over the compiled
 * fields of each subtype.
 */
class ProviderSettingCompiledModelTest {

    private val subtypes = listOf(
        ProviderSetting.OpenAI::class.java,
        ProviderSetting.Google::class.java,
        ProviderSetting.Claude::class.java,
    )

    @Test
    fun `ProviderSetting subtypes declare no presentation fields`() {
        for (klass in subtypes) {
            val fieldNames = klass.declaredFields.map { it.name }
            assertFalse(
                "${klass.simpleName} must not declare a `description` field",
                fieldNames.contains("description"),
            )
            assertFalse(
                "${klass.simpleName} must not declare a `shortDescription` field",
                fieldNames.contains("shortDescription"),
            )
        }
    }

    @Test
    fun `ProviderSetting subtype fields do not reference androidx types`() {
        for (klass in subtypes) {
            for (field in klass.declaredFields) {
                val typeName = field.type.name
                assertFalse(
                    "${klass.simpleName}.${field.name} leaks a UI type: $typeName",
                    typeName.contains("androidx."),
                )
            }
        }
    }
}
