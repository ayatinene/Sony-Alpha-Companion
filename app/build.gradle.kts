plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
val appVersionCode = 17
val appVersionName = "0.1.16-preview"

// Resolve the source revision at build time; tracked local edits append "-dirty"
// and any unusable Git response degrades to "unknown" (untracked files are ignored).
fun runGit(vararg args: String): String? = runCatching {
    val process = ProcessBuilder(listOf("git", "-C", rootProject.projectDir.absolutePath) + args)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor() == 0) output.trim() else null
}.getOrNull()

val fullShaPattern = Regex("[0-9a-f]{40}")
val shortShaPattern = Regex("[0-9a-f]{7,40}")
val gitInfo: Triple<String, String, Boolean>? = run {
    val fullSha = runGit("rev-parse", "HEAD")
    val shortSha = runGit("rev-parse", "--short=7", "HEAD")
    val dirty = runGit("status", "--porcelain", "--untracked-files=no")?.isNotEmpty() == true
    if (fullSha != null && shortSha != null && fullShaPattern.matches(fullSha) && shortShaPattern.matches(shortSha) && fullSha.startsWith(shortSha))
        Triple(fullSha, shortSha, dirty) else null
}
val gitRevision: String = gitInfo?.let { (_, shortSha, dirty) -> if (dirty) "$shortSha-dirty" else shortSha } ?: "unknown"
val versionedDebugApkName = "AlphaCompanion-$appVersionName-$gitRevision-debug.apk"
val versionedReleaseApkName = "AlphaCompanion-$appVersionName-$gitRevision-release.apk"

val signingPath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
android {
    namespace = "io.github.ayatinene.sonycompanion"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.ayatinene.sonycompanion"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GIT_REVISION", "\"$gitRevision\"")
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
    }
    buildTypes {
        getByName("debug") {
            isDebuggable = true
            if (signingPath != null) signingConfig = signingConfigs.getByName("companion")
        }
        getByName("release") {
            // A real, shippable Release build: never debuggable, and R8/shrinking stay
            // off so the published APK keeps exactly the behaviour the tests exercised.
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            if (signingPath != null) signingConfig = signingConfigs.getByName("companion")
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
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext)
    androidTestImplementation(libs.mockwebserver)
    testImplementation(libs.junit)
    testImplementation(libs.json.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
}

// Machine-readable provenance for the release workflow.
val buildVariant = "release"
val buildMetadataFile = layout.buildDirectory.file("build-metadata.json")
val writeBuildMetadata = tasks.register("writeBuildMetadata") {
    val output = buildMetadataFile
    val versionNameValue = appVersionName
    val versionCodeValue = appVersionCode
    val revisionValue = gitRevision
    val fullShaValue = gitInfo?.first.orEmpty()
    val dirtyValue = gitInfo?.third ?: false
    val variantValue = buildVariant
    inputs.property("versionName", versionNameValue)
    inputs.property("versionCode", versionCodeValue)
    inputs.property("gitRevision", revisionValue)
    inputs.property("gitSha", fullShaValue)
    inputs.property("dirty", dirtyValue)
    inputs.property("variant", variantValue)
    outputs.file(output)
    doLast {
        val target = output.get().asFile
        target.parentFile?.mkdirs()
        target.writeText(
            buildString {
                append("{\n")
                append("  \"versionName\": \"").append(versionNameValue).append("\",\n")
                append("  \"versionCode\": ").append(versionCodeValue).append(",\n")
                append("  \"gitRevision\": \"").append(revisionValue).append("\",\n")
                append("  \"gitSha\": \"").append(fullShaValue).append("\",\n")
                append("  \"variant\": \"").append(variantValue).append("\",\n")
                append("  \"dirty\": ").append(dirtyValue).append("\n")
                append("}\n")
            }
        )
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(writeBuildMetadata) }

// Keep the canonical app-<variant>.apk and add a version+revision named copy; CI
// publishes only the named copy. Any copy from an earlier revision is removed first
// and the task always re-runs, so a stale APK from a previous commit can never be
// uploaded from a reused build directory. The Release copy is only produced from a
// signed build (the fixed signing config), so an unsigned local release build skips it.
fun registerNamedApkTask(taskName: String, variant: String, versionedName: String) = tasks.register(taskName) {
    val source = layout.buildDirectory.file("outputs/apk/$variant/app-$variant.apk")
    val target = layout.buildDirectory.file("outputs/apk/$variant/$versionedName")
    inputs.file(source)
    outputs.file(target)
    outputs.upToDateWhen { false }
    onlyIf { source.get().asFile.isFile }
    doLast {
        val destinationDir = target.get().asFile.parentFile
        destinationDir?.listFiles { file -> file.isFile && file.name.startsWith("AlphaCompanion-") }?.forEach { it.delete() }
        val destination = target.get().asFile
        destination.parentFile?.mkdirs()
        source.get().asFile.copyTo(destination, overwrite = true)
    }
}
val nameDebugApk = registerNamedApkTask("nameDebugApk", "debug", versionedDebugApkName)
val nameReleaseApk = registerNamedApkTask("nameReleaseApk", "release", versionedReleaseApkName)
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(nameDebugApk) }
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(nameReleaseApk) }


