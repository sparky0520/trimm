plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val rootLocalEnv: Map<String, String> = rootProject.file("../../.env.local").takeIf { it.isFile }
    ?.readLines()?.mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) null
        else trimmed.substringBefore("=").trim() to trimmed.substringAfter("=").trim()
    }?.toMap().orEmpty()

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
        val plannerUrl = providers.gradleProperty("CTRLB_PLANNER_URL").orElse("").get()
        val plannerToken = providers.gradleProperty("CTRLB_PLANNER_TOKEN")
            .orElse(providers.environmentVariable("CTRLB_PLANNER_CLIENT_TOKEN"))
            .orElse(rootLocalEnv["CTRLB_PLANNER_CLIENT_TOKEN"] ?: "").get()
        val transcriptionUrl = providers.gradleProperty("CTRLB_TRANSCRIPTION_URL").orElse("").get()
        val catalogUrl = providers.gradleProperty("CTRLB_AUDIO_CATALOG_URL").orElse("").get()
        buildConfigField("String", "PLANNER_URL", "\"${plannerUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "PLANNER_TOKEN", "\"${plannerToken.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "TRANSCRIPTION_URL", "\"${transcriptionUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "AUDIO_CATALOG_URL", "\"${catalogUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
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
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
