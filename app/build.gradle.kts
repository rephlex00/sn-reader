plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.reader"
    // 37, not 36: androidx.core:core-ktx:1.19.0's AAR metadata requires
    // compileSdk >= 37. Needs `sdkmanager "platforms;android-37.1"`.
    // targetSdk stays 36 — compileSdk does not affect runtime behaviour.
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.reader"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":formats"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx) // lifecycleScope in ReaderActivity
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
