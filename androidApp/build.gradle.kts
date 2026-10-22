import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.github.terrakok.wikwok.androidApp"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        targetSdk = 37

        applicationId = "com.github.terrakok.wikwok.androidApp"
        versionCode = 13
        versionName = "1.7.0"
    }

    signingConfigs {
        if (properties.contains("key.file")) {
            create("release") {
                storeFile = file(properties["key.file"] as String)
                storePassword = properties["key.pwd"] as String
                keyAlias = properties["key.alias"] as String
                keyPassword = properties["key.alias.pwd"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            // Falls back to the debug key so a release build is installable without provisioning a
            // keystore. A distribution key stays opt-in through the key.* properties.
            signingConfig = if (properties.contains("key.file")) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    //https://android.izzysoft.de/articles/named/iod-scan-apkchecks#blobs
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":sharedUI"))
    implementation(libs.androidx.activityCompose)
}
