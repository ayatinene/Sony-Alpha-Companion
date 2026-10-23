plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "io.github.ayatinene.sonycompanion"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.ayatinene.sonycompanion"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1-preview"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity)
    implementation(libs.compose.ui)
    implementation(libs.compose.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.core)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.coroutines)
    implementation(libs.okhttp)
    implementation(libs.exif)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
}
