import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val buildNumberFile = rootProject.file("build_number.txt")
val buildProps = Properties()
if (buildNumberFile.exists()) {
    buildNumberFile.inputStream().use { buildProps.load(it) }
}

val releaseVersionName = buildProps.getProperty("version") ?: "0.1.20260602"
val releaseVersionCode = buildProps.getProperty("build")?.trim()?.toIntOrNull() ?: 1
// The day of the build, which the version stopped carrying when its last
// component became the build number. Shown on the About screen and nowhere else.
val releaseBuildDate = buildProps.getProperty("build_date")?.trim().orEmpty()

val keyProperties = Properties()
val keyPropertiesFile = sequenceOf(
    File("/home/e/.my-safe/key.properties"),
    rootProject.file("key.properties")
).firstOrNull { it.exists() } ?: rootProject.file("key.properties")
val hasReleaseSigning = keyPropertiesFile.exists().also { exists ->
    if (exists) {
        keyPropertiesFile.inputStream().use { keyProperties.load(it) }
    }
}
val keyStoreFile = (keyProperties["storeFile"] as String?)?.let { rawPath ->
    val candidate = File(rawPath)
    if (candidate.isAbsolute) candidate else File(keyPropertiesFile.parentFile, rawPath)
}

android {
    namespace = "com.myplayer"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.myplayer"
        minSdk = 31
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        buildConfigField("String", "BUILD_DATE", "\"$releaseBuildDate\"")
    }

    sourceSets {
        // The shared in-app updater, compiled from ../updater instead of pulled in
        // as a module: one copy of those sources serves every project here, with no
        // AAR to rebuild. The kotlin set and not the java one: the Kotlin source
        // set no longer inherits java, so .kt files put there are never compiled.
        getByName("main") {
            kotlin.directories.add("$rootDir/../updater/android/src")
            res.directories.add("$rootDir/../updater/android/res")
            // The shared About dialog, on the same terms. No res set: its strings
            // are in AboutStrings.kt, where a release build's resource shrinker
            // cannot drop them.
            kotlin.directories.add("$rootDir/../about/android/src")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = keyStoreFile
                storePassword = keyProperties["storePassword"] as String
                keyAlias = keyProperties["keyAlias"] as String
                keyPassword = keyProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BUILD_DATE below is a BuildConfig field, and AGP generates none
        // without this.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ---------- APK names the scripts can read ----------
//
// Gradle writes app-<abi>-<type>.apk, which says nothing about which build it
// is. Everything downstream — 19-LinkOut.sh, 22-RelUpload.sh, 23-ToUpdate.sh,
// the .apkx link — reads the version and the build number out of the file name
// instead, so the rename happens here, once, right after the assemble.
//
// One shape for every artifact of every project here:
//
//   <project>-<version>-<build>-<abi>.apk         a release
//   <project>-<version>-<build>-<abi>-debug.apk   a debug build
//
// A release says nothing about its build type: that is what an artifact is
// unless it says otherwise, and the word in every name only makes the listing
// harder to read. A debug build does say so, because it is the one that must
// never be mistaken for the other.
//
// The name below must match the one the scripts use.

abstract class RenameApks : DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val projectName: Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val versionName: Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val buildType: Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val abis: ListProperty<String>

    @get:org.gradle.api.tasks.Internal
    abstract val outputDir: DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun rename() {
        val outDir = outputDir.get().asFile
        val type = buildType.get()
        val prefix = "${projectName.get()}-${versionName.get()}"
        val tail = if (type == "release") "" else "-$type"

        fun move(src: File, dst: File) {
            if (!src.exists()) return
            if (dst.exists()) dst.delete()
            src.renameTo(dst)
        }

        abis.get().forEach { abi ->
            move(File(outDir, "app-$abi-$type.apk"), File(outDir, "$prefix-$abi$tail.apk"))
        }
        // The unsplit output, for a variant the ABI splits do not apply to.
        move(File(outDir, "app-$type.apk"), File(outDir, "$prefix$tail.apk"))
    }
}

val renameReleaseApks by tasks.registering(RenameApks::class) {
    projectName.set("myplayer")
    versionName.set(releaseVersionName)
    buildType.set("release")
    abis.set(listOf("universal", "arm64-v8a", "armeabi-v7a", "x86_64"))
    outputDir.set(layout.buildDirectory.dir("outputs/apk/release"))
}

// Not a finalizer of assembleDebug, the way the release one is: the debug APK is
// also what an instrumented test run installs, and that install reads the name
// out of Gradle's own output metadata. The debug build step asks for this task
// by name instead, so a test run from the IDE still finds app-<abi>-debug.apk.
val renameDebugApks by tasks.registering(RenameApks::class) {
    dependsOn("assembleDebug")
    projectName.set("myplayer")
    versionName.set(releaseVersionName)
    buildType.set("debug")
    abis.set(listOf("universal", "arm64-v8a", "armeabi-v7a", "x86_64"))
    outputDir.set(layout.buildDirectory.dir("outputs/apk/debug"))
}

tasks.configureEach {
    if (name == "assembleRelease") {
        finalizedBy(renameReleaseApks)
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
