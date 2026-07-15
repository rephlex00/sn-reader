plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.reader.formats"
    // 37, not 36: androidx.core:core-ktx:1.19.0's AAR metadata requires
    // compileSdk >= 37. Needs `sdkmanager "platforms;android-37.1"`.
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
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":engine"))
    implementation(libs.jsoup)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
}
