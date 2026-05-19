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
    testOptions {
        // Return default values (null / 0 / false) for Android framework calls in JVM unit
        // tests instead of throwing "not mocked" exceptions. Required because production
        // code (e.g. LiteRtToolPrefix) calls android.util.Log which isn't available on JVM.
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        getByName("main").jniLibs.setSrcDirs(listOf("src/main/jniLibs"))
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
    // LiteRT-LM runtime: loads .litertlm model files produced by the LiteRT-LM toolchain.
    //
    // Pinned to 0.11.0 to MATCH Google AI Edge Gallery's working configuration. Gallery
    // ships 0.11.0 (gradle/libs.versions.toml in github.com/google-ai-edge/gallery) and
    // successfully runs Gemma 4 multimodal on devices including Snapdragon 8 Gen 1
    // (Nothing Phone 1 / Adreno 642L) where our prior 0.12.0 bump native-SIGSEGV'd inside
    // liblitertlm_jni.so during vision-encoder init. Until we have an upstream signal
    // that 0.12+ is safe on the device classes Gallery supports, we stay aligned with
    // Gallery's reference build.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")

    testImplementation(libs.junit)
}
