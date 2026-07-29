plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val rootLocalEnv: Map<String, String> = rootProject.file(".env.local").takeIf { it.isFile }
    ?.readLines()?.mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) null
        else trimmed.substringBefore("=").trim() to trimmed.substringAfter("=").trim()
    }?.toMap().orEmpty()

val uploadStorePath = rootLocalEnv["CTRLB_ANDROID_UPLOAD_STORE_FILE"].orEmpty()
val uploadStorePassword = rootLocalEnv["CTRLB_ANDROID_UPLOAD_STORE_PASSWORD"].orEmpty()
val uploadKeyAlias = rootLocalEnv["CTRLB_ANDROID_UPLOAD_KEY_ALIAS"].orEmpty()
val uploadKeyPassword = rootLocalEnv["CTRLB_ANDROID_UPLOAD_KEY_PASSWORD"].orEmpty()

android {
    namespace = "ai.ctrlb.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.ctrlb.mobile"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val backendUrl = providers.gradleProperty("CTRLB_BACKEND_URL")
            .orElse(providers.environmentVariable("CTRLB_BACKEND_URL"))
            .orElse(rootLocalEnv["CTRLB_BACKEND_URL"] ?: "")
            .get().trimEnd('/')
        val plannerUrl = providers.gradleProperty("CTRLB_PLANNER_URL").orElse(
            backendUrl.takeIf { it.isNotEmpty() }?.let { "$it/v1/mobile/plan" } ?: ""
        ).get()
        val agentRunsUrl = providers.gradleProperty("CTRLB_AGENT_RUNS_URL").orElse(
            backendUrl.takeIf { it.isNotEmpty() }?.let { "$it/v1/agent/runs" } ?: ""
        ).get()
        val transcriptionUrl = providers.gradleProperty("CTRLB_TRANSCRIPTION_URL").orElse(
            backendUrl.takeIf { it.isNotEmpty() }?.let { "$it/v1/transcribe" } ?: ""
        ).get()
        val catalogUrl = providers.gradleProperty("CTRLB_AUDIO_CATALOG_URL").orElse("").get()
        val clerkPublishableKey = providers.gradleProperty("CTRLB_CLERK_PUBLISHABLE_KEY")
            .orElse(providers.environmentVariable("CTRLB_CLERK_PUBLISHABLE_KEY"))
            .orElse(rootLocalEnv["CTRLB_CLERK_PUBLISHABLE_KEY"] ?: rootLocalEnv["VITE_CLERK_PUBLISHABLE_KEY"] ?: "").get()
        buildConfigField("String", "PLANNER_URL", "\"${plannerUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "AGENT_RUNS_URL", "\"${agentRunsUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "TRANSCRIPTION_URL", "\"${transcriptionUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "AUDIO_CATALOG_URL", "\"${catalogUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "CLERK_PUBLISHABLE_KEY", "\"${clerkPublishableKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    signingConfigs {
        create("release") {
            if (uploadStorePath.isNotEmpty()) {
                storeFile = rootProject.file(uploadStorePath)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }
    buildTypes {
        debug {
            val allowBootstrap = providers.gradleProperty("CTRLB_ALLOW_BOOTSTRAP_AUTH")
                .orElse(providers.environmentVariable("CTRLB_ALLOW_BOOTSTRAP_AUTH"))
                .orElse(rootLocalEnv["CTRLB_ALLOW_BOOTSTRAP_AUTH"] ?: "false").get().equals("true", ignoreCase = true)
            val bootstrapToken = if (allowBootstrap) {
                providers.gradleProperty("CTRLB_PLANNER_TOKEN")
                    .orElse(providers.environmentVariable("CTRLB_PLANNER_CLIENT_TOKEN"))
                    .orElse(rootLocalEnv["CTRLB_PLANNER_CLIENT_TOKEN"] ?: "").get()
            } else ""
            buildConfigField("String", "PLANNER_TOKEN", "\"${bootstrapToken.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "PLANNER_TOKEN", "\"\"")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-ui:1.10.0")
    implementation("androidx.media3:media3-transformer:1.10.0")
    implementation("androidx.media3:media3-effect:1.10.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.clerk:clerk-android-ui:1.0.28")
    implementation("com.android.billingclient:billing-ktx:9.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
