plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.reader.formats"
    // 37, not 36: kept in sync with :app, which needs compileSdk >= 37 for
    // androidx.core:core-ktx:1.19.0's AAR metadata. :formats has no androidx dependency
    // of its own requiring this — it just builds against the same SDK as the app that
    // consumes it. Needs `sdkmanager "platforms;android-37.1"`.
    // targetSdk stays 36 — compileSdk does not affect runtime behaviour.
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        targetSdk = 36
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":engine"))
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
}
