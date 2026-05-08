import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.locallm"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("main").jniLibs.srcDirs("src/main/jniLibs")
    }
    packaging {
        jniLibs {
            // Avoid extracting native libs at install time so System.loadLibrary path stays cheap.
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    api(project(":ai"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    // MediaPipe LLM Inference: the LiteRT path. Pinned to the tasks-genai 0.10.x line so
    // upgrades happen deliberately, never on a `+` floating-version bump.
    implementation("com.google.mediapipe:tasks-genai:0.10.21")

    testImplementation(libs.junit)
}
