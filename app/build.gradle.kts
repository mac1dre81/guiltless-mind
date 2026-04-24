import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
}

apply(plugin = "com.google.dagger.hilt.android")

val keystoreProps = Properties().apply {
    val propFile = file("keystore.properties")
    if (propFile.exists()) {
        load(propFile.inputStream())
    }
}

val sampleAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val releaseAdmobAppId = providers.gradleProperty("DOCEDITOR_ADMOB_APP_ID")
    .orElse(providers.environmentVariable("DOCEDITOR_ADMOB_APP_ID"))
    .orNull
val requiredSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasReleaseSigning = requiredSigningKeys.all { key ->
    !keystoreProps.getProperty(key).isNullOrBlank()
}

android {
    namespace = "com.document.editor"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.document.editor"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "com.document.editor.HiltTestRunner"
        manifestPlaceholders["admobAppId"] = sampleAdmobAppId
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["admobAppId"] = sampleAdmobAppId
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            manifestPlaceholders["admobAppId"] = releaseAdmobAppId ?: sampleAdmobAppId
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
        viewBinding = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        lintConfig = file("lint.xml")
    }
}

gradle.taskGraph.whenReady {
    val isReleaseGraph = allTasks.any { task ->
        task.name.contains("Release", ignoreCase = true)
    }
    if (isReleaseGraph) {
        if (!hasReleaseSigning) {
            throw GradleException(
                "Release signing is not configured. Add storeFile, storePassword, keyAlias, and keyPassword to app/keystore.properties before building a signed release AAB."
            )
        }
        if (releaseAdmobAppId.isNullOrBlank()) {
            throw GradleException(
                "DOCEDITOR_ADMOB_APP_ID is missing. Set it in your Gradle properties before generating a release bundle."
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.markwon.core)
    implementation(libs.markwon.editor)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.hiltAndroid)
    add("ksp", libs.hiltCompiler)

    // Play Billing & AdMob
    implementation(libs.billing.ktx)
    implementation(libs.play.services.ads)
    implementation(libs.play.services.mlkit.document.scanner)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.kotlinx.coroutines.play.services)

    // CameraX (live view + frame analysis)
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // OpenCV for on-device preprocessing (deskew, contrast, adaptive threshold)
    implementation("org.opencv:opencv:4.10.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.hiltAndroidTesting)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    add("kspAndroidTest", libs.hiltCompiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
