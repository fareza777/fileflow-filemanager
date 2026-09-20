import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val keyProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// A release built without key.properties used to fall back to the debug key
// silently: the artefact looked finished, then could never be uploaded — or was
// installed once and could never be updated again, because no later build would
// ever match that signature. Fail at configuration time instead, with the
// missing keys named.
val missingSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .filter { keyProps.getProperty(it).isNullOrBlank() }

val hasUploadSigning = missingSigningKeys.isEmpty()

// Checked when a release is actually requested, not during configuration: a
// missing key file must not break Gradle sync or a debug build.
//
// This used to be a `doFirst` on the release tasks, which made every one of
// them capture a reference to this script object. The configuration cache
// cannot serialize that, so the build failed outright with "cannot serialize
// Gradle script object references" the moment a release task was up to date
// and actually executed. Asking the task graph instead keeps the check in the
// configuration phase, where nothing has to be cached.
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any {
        it.name == "assembleRelease" || it.name == "bundleRelease"
    }
    if (wantsRelease && missingSigningKeys.isNotEmpty()) {
        throw GradleException(
            "Refusing to build an unsigned release: key.properties is missing " +
                missingSigningKeys.joinToString(", ") + ".\n" +
                "Add them at the project root (the file is gitignored), or build " +
                "the debug variant instead."
        )
    }
}

// AdMob ids come from local.properties (gitignored); the fallbacks are
// Google's official test ids so a clean clone always runs. A malformed
// AdMob application id crashes the app at startup, so a default matters.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun adId(key: String, testDefault: String): String =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: testDefault

val admobAppId = adId("admob.appId", "ca-app-pub-3940256099942544~3347511713")
val admobBannerId = adId("admob.bannerUnitId", "ca-app-pub-3940256099942544/6300978111")

android {
    namespace = "com.fileflow.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fileflow.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"$admobBannerId\"")
        buildConfigField(
            "boolean", "ADMOB_TEST_IDS", admobAppId.contains("3940256099942544").toString()
        )
    }

    signingConfigs {
        if (hasUploadSigning) {
            create("upload") {
                storeFile = rootProject.file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "FileFlow Debug"
            // Dev builds never touch production ad units: impressions from
            // your own device are an AdMob policy violation.
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField(
                "String", "ADMOB_BANNER_UNIT_ID",
                "\"ca-app-pub-3940256099942544/6300978111\"",
            )
            buildConfigField("boolean", "ADMOB_TEST_IDS", "true")
        }
        release {
            manifestPlaceholders["appLabel"] = "FileFlow"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Deliberately null rather than the debug key when key.properties is
            // missing, so the artefact is honestly named `app-release-unsigned`
            // and the guard above reports why.
            signingConfig = if (hasUploadSigning) signingConfigs.getByName("upload") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/DEPENDENCIES"
    }
    lint {
        // Lint stays configured and runnable on demand (`./gradlew lintDebug`),
        // but it is not wired into every release build: on this project a full
        // release lint pass runs for tens of minutes and blocks the bundle,
        // which makes it something to avoid rather than something to use.
        // Run it deliberately, not on every build.
        checkReleaseBuilds = false
        abortOnError = false
        warningsAsErrors = false
        // All-files access is the documented, Play-approved use case for a file
        // manager, so the check that assumes otherwise does not apply.
        disable += setOf("ScopedStorage", "QueryAllPackagesPermission", "DataExtractionRules")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.splashscreen)

    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // Remote storage. WebDAV and S3 are hand-written over OkHttp; SMB and SFTP
    // bring their own protocol implementations because neither is a weekend's
    // worth of code, and both are pure Java so nothing native is built here.
    implementation(libs.okhttp)
    implementation(libs.jsch)
    implementation(libs.jcifs.ng)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
