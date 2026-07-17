import com.android.build.api.dsl.Packaging
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.baselineprofile)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}

fun localStringProperty(name: String, envName: String? = null): String {
    val value = (localProperties.getProperty(name)
        ?: envName?.let(System::getenv)
        ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$value\""
}

private fun loadPlainEnvironmentFile(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    return file.readLines().mapNotNull { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
        val separator = line.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val key = line.substring(0, separator).trim()
        val rawValue = line.substring(separator + 1).trim()
        val value = if (
            rawValue.length >= 2 &&
            ((rawValue.first() == '"' && rawValue.last() == '"') ||
                (rawValue.first() == '\'' && rawValue.last() == '\''))
        ) rawValue.substring(1, rawValue.lastIndex) else rawValue
        key to value
    }.toMap()
}

private fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val voiceAgentLocalEnvironment = loadPlainEnvironmentFile(
    File(System.getProperty("user.home"), ".config/voice-lab/local.env"),
)

fun resolvedVoiceAgentSetting(propertyName: String, environmentName: String): String =
    providers.gradleProperty(propertyName).orNull
        ?: localProperties.getProperty(propertyName)
        ?: System.getenv(environmentName)
        ?: voiceAgentLocalEnvironment[environmentName]
        ?: ""

val voiceAgentSentryDsn = resolvedVoiceAgentSetting("voiceAgentSentryDsn", "VOICE_AGENT_SENTRY_DSN")
val voiceAgentSentryEnvironment = resolvedVoiceAgentSetting(
    "voiceAgentSentryEnvironment",
    "VOICE_AGENT_SENTRY_ENVIRONMENT",
)
val voiceAgentSentryTracesSampleRate = resolvedVoiceAgentSetting(
    "voiceAgentSentryTracesSampleRate",
    "VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE",
)

val validateVoiceAgentSentryDebug by tasks.registering {
    group = "verification"
    description = "Validates Voice Agent Sentry settings before packaging a debug APK."
    val dsnValue = voiceAgentSentryDsn
    val environmentValue = voiceAgentSentryEnvironment
    val tracesSampleRateValue = voiceAgentSentryTracesSampleRate
    doLast {
        require(dsnValue.isNotBlank()) {
            "VOICE_AGENT_SENTRY_DSN is required for debug APK builds; use a Gradle/local property, process environment, or ~/.config/voice-lab/local.env"
        }
        val dsn = runCatching { URI(dsnValue) }.getOrNull()
        require(
            dsn != null && dsn.scheme in setOf("http", "https") &&
                dsn.rawUserInfo?.substringBefore(':')?.isNotBlank() == true &&
                !dsn.host.isNullOrBlank() && dsn.path?.trim('/')?.isNotBlank() == true
        ) { "VOICE_AGENT_SENTRY_DSN is invalid for a debug APK build" }
        require(environmentValue.isNotBlank()) {
            "VOICE_AGENT_SENTRY_ENVIRONMENT is required for debug APK builds"
        }
        val rate = tracesSampleRateValue.toDoubleOrNull()
        require(rate != null && rate.isFinite() && rate in 0.0..1.0) {
            "VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE must be a finite number in 0.0..1.0 for debug APK builds"
        }
        logger.lifecycle("Voice Agent Sentry debug configuration verified")
    }
}

android {
    namespace = "me.rerere.rikkahub"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.rerere.rikkahub"
        minSdk = 26
        targetSdk = 37
        versionCode = 169
        versionName = "2.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "VOICE_AGENT_BASE_URL_OVERRIDE", localStringProperty("voiceAgentBaseUrlOverride", "VOICE_AGENT_BASE_URL_OVERRIDE"))
        buildConfigField("String", "VOICE_AGENT_HERMES_E2E_EXPECTED_HASH", "\"\"")
        buildConfigField("String", "VOICE_AGENT_SENTRY_DSN", voiceAgentSentryDsn.asBuildConfigString())
        buildConfigField("String", "VOICE_AGENT_SENTRY_ENVIRONMENT", voiceAgentSentryEnvironment.asBuildConfigString())
        buildConfigField(
            "String",
            "VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE",
            voiceAgentSentryTracesSampleRate.asBuildConfigString(),
        )

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    splits {
        abi {
            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProperties.getProperty("storeFile")
            val storePasswordValue = localProperties.getProperty("storePassword")
            val keyAliasValue = localProperties.getProperty("keyAlias")
            val keyPasswordValue = localProperties.getProperty("keyPassword")

            if (storeFilePath != null && storePasswordValue != null &&
                keyAliasValue != null && keyPasswordValue != null
            ) {
                storeFile = file(storeFilePath)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
            buildConfigField("String", "VOICE_AGENT_HERMES_E2E_EXPECTED_HASH", localStringProperty("voiceAgentHermesE2eExpectedHash", "VOICE_AGENT_HERMES_E2E_EXPECTED_HASH"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
    androidResources {
        generateLocaleConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/*/libtermux.so"
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        compilerOptions.optIn.add("androidx.navigation3.runtime.ExperimentalNavigation3Api")
    }
}

val voiceAgentSentryProtectedDebugTasks = setOf(
    "assembleDebug",
    "packageDebug",
    "packageDebugUniversalApk",
    "installDebug",
)
tasks.configureEach {
    if (name in voiceAgentSentryProtectedDebugTasks) {
        dependsOn(validateVoiceAgentSentryDebug)
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

tasks.register("buildAll") {
    dependsOn("assembleRelease", "bundleRelease")
    description = "Build both APK and AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.termux.terminal.view)
    implementation(libs.guava.listenablefuture)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive.navigation3)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.config)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)

    // Haze (background blur)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.materials)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // jetbrains markdown parser
    implementation(libs.jetbrains.markdown)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.json)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ucrop
    implementation(libs.ucrop)

    // pebble (template engine)
    implementation(libs.pebble)

    // java-diff-utils (unified diff)
    implementation(libs.diffutils)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)

    // serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sentry.android)

    // zxing
    implementation(libs.zxing.core)

    // quickie (qrcode scanner)
    implementation(libs.quickie.bundled)
    implementation(libs.barcode.scanning)
    implementation(libs.androidx.camera.core)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    baselineProfile(project(":app:baselineprofile"))
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Apache Commons Text
    implementation(libs.commons.text)

    // Toast (Sonner)
    implementation(libs.sonner)

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // lucide icons
    implementation(libs.lucide.icons)
    implementation(libs.huge.icons)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/rikkahub/jlatexmath-android
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.font.greek)
    implementation(libs.jlatexmath.font.cyrillic)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // jmDNS (mDNS/Bonjour for .local hostname)
    implementation(libs.jmdns)

    // SLF4J Android binding — routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)

    // modules
    implementation(project(":ai"))
    implementation(project(":web"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":speech"))
    implementation(project(":common"))
    implementation(project(":material3"))
    implementation(project(":workspace"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(kotlin("reflect"))

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // tests
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
