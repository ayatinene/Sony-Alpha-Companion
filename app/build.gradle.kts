plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
val signingPath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
android {
    namespace = "io.github.ayatinene.sonycompanion"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.ayatinene.sonycompanion"
        minSdk = 29
        targetSdk = 36
        versionCode = 8
        versionName = "0.1.7-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (signingPath != null) {
        signingConfigs {
            create("companion") {
                storeFile = file(signingPath)
                storeType = "PKCS12"
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
        buildTypes {
            getByName("debug") { signingConfig = signingConfigs.getByName("companion") }
            getByName("release") { signingConfig = signingConfigs.getByName("companion") }
        }
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
    debugImplementation(libs.compose.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext)
    testImplementation(libs.junit)
    testImplementation(libs.json.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
}
