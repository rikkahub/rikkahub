import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("rikkahub.android.library.compose")
}

android {
    namespace = "me.rerere.ui"
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.ink.authoring.compose)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.geometry)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.strokes)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.huge.icons)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
