plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.videogen"
}

dependencies {
    implementation(project(":common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
