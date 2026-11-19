plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vaaniflow.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.vaaniflow.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"

        val defaultWsUrl = providers.gradleProperty("GATEWAY_WS_URL")
            .orElse("ws://10.0.2.2:8787/v1/device-channel")
            .get()
        buildConfigField("String", "DEFAULT_GATEWAY_WS_URL", "\"${defaultWsUrl.replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // In-app wireless ADB: pairs with this phone's own adbd to launch the shell-UID audio server.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    // QR pairing with the admin console (Google code scanner: no camera permission needed).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // UI: Jetpack Compose + Material 3 (phone app screens, in-call screen, VaaniFlow AI settings).
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Screenshot tests of the screens on the JVM (Robolectric + Roborazzi): ./gradlew :app:testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
