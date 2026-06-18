plugins {
    alias(libs.plugins.android.library)
}

val webUiDir = rootProject.layout.projectDirectory.dir("web-ui")
val webStaticResourcesDir = layout.projectDirectory.dir("src/main/resources/static")

// Install web-ui dependencies. Up-to-date when bun.lock + package.json haven't
// changed since the last successful install, so it's a no-op on every build
// after the first. Without this step, the buildWebUi task fails on a clean
// checkout with `react-router: command not found` until someone manually runs
// `bun install` in web-ui/.
val installWebUiDeps = tasks.register<Exec>("installWebUiDeps") {
    group = "build"
    description = "Install web-ui dependencies via bun if the lockfile changed."

    workingDir = webUiDir.asFile
    commandLine("bun", "install", "--frozen-lockfile")

    inputs.files(
        webUiDir.file("package.json"),
        webUiDir.file("bun.lock")
    )
    outputs.dir(webUiDir.dir("node_modules"))
}

val buildWebUi = tasks.register<Exec>("buildWebUi") {
    group = "build"
    description = "Build web-ui and copy its static output into the web module resources."

    dependsOn(installWebUiDeps)

    workingDir = webUiDir.asFile
    commandLine("pnpm", "run", "build")

    inputs.files(
        webUiDir.file("package.json"),
        webUiDir.file("pnpm-lock.yaml"),
        webUiDir.file("components.json"),
        webUiDir.file("copy.ts"),
        webUiDir.file("react-router.config.ts"),
        webUiDir.file("tsconfig.json"),
        webUiDir.file("vite.config.ts"),
        webUiDir.file("vite-env.d.ts")
    )
    inputs.dir(webUiDir.dir("app"))
    inputs.dir(webUiDir.dir("public"))
    outputs.dir(webStaticResourcesDir)
}

android {
    namespace = "me.rerere.rikkahub.web"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.named("preBuild") {
    dependsOn(buildWebUi)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // ktor server
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.conditional.headers)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.cors)
    api(libs.ktor.server.auth)
    api(libs.ktor.server.auth.jwt)
    api(libs.ktor.server.core)
    implementation(libs.ktor.server.host.common)
    api(libs.ktor.server.content.negotiation)
    api(libs.ktor.server.status.pages)
    api(libs.ktor.server.sse)
    api(libs.ktor.server.cio)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
