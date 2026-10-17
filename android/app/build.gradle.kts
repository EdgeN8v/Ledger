import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(name: String, fallback: String) = (localProps.getProperty(name) ?: fallback).replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.ledger.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ledger.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 28
        versionName = "0.4.15"
        // Where the app looks for your server out of the box. Set them in android/local.properties (never committed):
        //   ledger.defaultServer=https://ledger.example.com/     ledger.lanServer=http://192.168.1.10:8000/
        buildConfigField("String", "DEFAULT_SERVER", "\"" + localProp("ledger.defaultServer", "http://192.168.1.10:8000/") + "\"")
        buildConfigField("String", "LAN_SERVER", "\"" + localProp("ledger.lanServer", "") + "\"")
    }

    buildTypes {
        release {
            // R8 drops the unused ~40 MB of library code (the icon set alone was most of the download); signed with the same
            // key as the builds already on the phones, so it installs over them as an update
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material") // pullrefresh
    implementation("androidx.compose.material:material-icons-extended") // pulls in Icons.Filled.Mic etc, not in the core set
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
