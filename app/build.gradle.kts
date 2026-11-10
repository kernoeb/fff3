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
        // CI passes -PversionName from the release tag. Local builds keep the defaults.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "0.1.0"
    }

    // Library strings ship in about 80 languages. The app is in English, and the user reads French.
    // Lint 9.5 crashes while analysing some Compose files ("bug in lint"), which blocks release builds.
    lint {
        checkReleaseBuilds = false
    }

    androidResources {
        localeFilters += listOf("en", "fr")
    }

    // The fff3 key lives outside the repo: in ~/.gradle/gradle.properties locally, in secrets on GitHub.
    // Without it, release builds use the debug key, and Android refuses them as an update to fff3.
    val releaseKeystore = providers.gradleProperty("fff3.keystore").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.gradleProperty("fff3.keystorePassword").get()
                keyAlias = providers.gradleProperty("fff3.keyAlias").orNull ?: "fff3"
                keyPassword = storePassword
            }
        }
    }
    if (releaseKeystore == null) logger.warn("fff3: no fff3.keystore property, release builds use the debug key")

    buildTypes {
        release {
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
