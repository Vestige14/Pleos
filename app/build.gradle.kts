plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.memoryvault"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "app.memoryvault"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-bootstrap"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    useLibrary("android.car")

    buildFeatures {
        compose = true
    }

    signingConfigs.getByName("debug") {
        storeFile = rootProject.file(".local/android/debug.keystore")
    }

    buildTypes {
        getByName("debug") {
            // SDK privacy optimization must run; debuggable R8 preserves vendor logger calls.
            isMinifyEnabled = true
            isDebuggable = false
            proguardFiles("vendor-log-protection.pro")
        }
        getByName("release") {
            isMinifyEnabled = true
            isDebuggable = false
            proguardFiles("vendor-log-protection.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Room is the offline source of truth; schemas are kept with the source.
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
