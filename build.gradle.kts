// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.spotless)
}

spotless {
    // 只格式化相对 origin/master 有改动的文件，避免一次性重排整个仓库
    ratchetFrom("origin/master")

    // Spotless 只从 .editorconfig 读标准属性，ktlint 的风格和规则开关得写在这里
    val ktlintVersion = libs.versions.ktlint.get()
    val ktlintConfig = mapOf(
        "ktlint_code_style" to "intellij_idea",
        "max_line_length" to 120,
        // 以下规则和现有写法冲突较多，只会制造大量改动
        "ktlint_standard_trailing-comma-on-call-site" to "disabled",
        "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
        "ktlint_standard_function-signature" to "disabled",
        "ktlint_standard_class-signature" to "disabled",
        "ktlint_standard_function-expression-body" to "disabled",
        "ktlint_standard_blank-line-between-when-conditions" to "disabled",
        "ktlint_standard_wrapping" to "disabled",
    )
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "material3/material-color-utilities/**")
        ktlint(ktlintVersion).editorConfigOverride(ktlintConfig)
        // 只用 ktlint 做格式化，命名、文件名、行宽这类没法自动修的问题不拦
        suppressLintsFor { step = "ktlint" }
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(ktlintVersion).editorConfigOverride(ktlintConfig)
        suppressLintsFor { step = "ktlint" }
    }
}
