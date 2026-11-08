plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.herdertower.phone"
    compileSdk = 36

    // Every build a higher version (the commit count), so a new APK always installs over the last one.
    val commits = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }.getOrElse(1)

    defaultConfig {
        applicationId = "dev.herdertower.phone"
        minSdk = 29
        targetSdk = 36
        versionCode = commits
        versionName = "0.$commits"
    }

    signingConfigs {
        // The same key for every build on this machine (the SDK's debug key, ~/.android/debug.keystore,
        // or one named in ~/.gradle/gradle.properties as towerKeystore/towerKeystorePassword/towerKeyAlias),
        // so an update installs in place over the last APK instead of asking to uninstall.
        getByName("debug") {
            providers.gradleProperty("towerKeystore").orNull?.let { path ->
                storeFile = file(path)
                storePassword = providers.gradleProperty("towerKeystorePassword").get()
                keyAlias = providers.gradleProperty("towerKeyAlias").get()
                keyPassword = providers.gradleProperty("towerKeystorePassword").get()
            }
        }
    }

    buildTypes {
        debug {
            // `-Pdemo`: a second copy (dev.herdertower.phone.demo) beside the real one, e.g. paired with tools/fake_tower.py for screenshots.
            if (providers.gradleProperty("demo").isPresent) applicationIdSuffix = ".demo"
        }
        release {
            // Signed with the debug key unless a real one is configured: installable for local use.
            signingConfig = signingConfigs.getByName("debug")
            if (providers.gradleProperty("demo").isPresent) applicationIdSuffix = ".demo"
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
}
