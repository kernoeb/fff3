import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.kernoeb.fff3"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kernoeb.fff3"
        minSdk {
            version = release(33)
        }
        targetSdk {
            version = release(37)
        }
        versionCode = 1
        versionName = "0.1.0"
    }

    // Library strings ship in about 80 languages. The app is in English, and the user reads French.
    androidResources {
        localeFilters += listOf("en", "fr")
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            // Personal use, sideloaded: the debug key lets you install release builds directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // Licence texts, Kotlin reflection metadata and coroutine debug probes: unused at runtime.
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/androidx/**",
                "/kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.datastore)
    implementation(libs.coroutines.android)
    implementation(libs.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
