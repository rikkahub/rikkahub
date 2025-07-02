import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.google.services)
  alias(libs.plugins.firebase.crashlytics)
}

android {
  namespace = "me.rerere.rikkahub"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.luosc.rikkahub"
    minSdk = 26
    targetSdk = 36
    versionCode = 70
    versionName = "1.1.2"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    splits {
      abi {
        reset()
        include("arm64-v8a", "x86_64")
        isEnable = true
        isUniversalApk = true
      }
    }
  }

  signingConfigs {
    create("release") {
      val localProperties = Properties()
      val localPropertiesFile = rootProject.file("local.properties")

      if (localPropertiesFile.exists()) {
        localProperties.load(FileInputStream(localPropertiesFile))

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
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  androidResources {
    generateLocaleConfig = true
  }
  applicationVariants.all {
    outputs.all {
      this as com.android.build.gradle.internal.api.ApkVariantOutputImpl

      val variantName = name
      val apkName = "rikkahub_" + defaultConfig.versionName + "_" + variantName + ".apk"

      outputFileName = apkName
    }
  }
  tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
    compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
    compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
    compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
    compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
    compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
  }
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.browser)

  // Compose
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.material3)

  // Firebase
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.crashlytics)

  // DataStore
  implementation(libs.androidx.datastore.preferences)

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

  // pebble (template engine)
  implementation(libs.pebble)

  // coil
  implementation(libs.coil.compose)
  implementation(libs.coil.okhttp)
  implementation(libs.coil.svg)

  // serialization
  implementation(libs.kotlinx.serialization.json)

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
  ksp(libs.androidx.room.compiler)

  // Paging3
  implementation(libs.androidx.paging.runtime)
  implementation(libs.androidx.paging.compose)

  // WebDav
  implementation(libs.dav4jvm) {
    exclude(group = "org.ogce", module = "xpp3")
  }

  // Apache Commons Text
  implementation(libs.commons.text)

  // Compose Cropper
  implementation(libs.ucrop)

  // Toast (Sonner)
  implementation(libs.sonner)

  // Reorderable (https://github.com/Calvin-LL/Reorderable/)
  implementation(libs.reorderable)

  // Permission
  implementation(libs.permissions.compose)

  // lucide icons
  implementation(libs.lucide.icons)

  // image viewer
  implementation(libs.image.viewer)

  // JLatexMath
  // https://github.com/rikkahub/jlatexmath-android
  implementation(libs.jlatexmath)
  implementation(libs.jlatexmath.font.greek)
  implementation(libs.jlatexmath.font.cyrillic)

  // mcp
  implementation(libs.modelcontextprotocol.kotlin.sdk)

  // modules
  implementation(project(":ai"))
  implementation(project(":highlight"))
  implementation(project(":search"))
  implementation(project(":rag"))
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
  implementation(kotlin("reflect"))

  // Leak Canary
  debugImplementation(libs.leakcanary.android)

  // tests
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
}