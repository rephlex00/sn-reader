plugins {
    alias(libs.plugins.android.application)
}

// Version is stamped by CI (see .github/workflows/release.yml), which passes
// -PappVersionName=YYYY.MM.build and a monotonic -PappVersionCode. Local builds fall back to a
// clearly-marked dev version so a hand-built APK is never mistaken for a release artifact.
val appVersionName: String = (project.findProperty("appVersionName") as String?) ?: "0.0.0-dev"
val appVersionCode: Int = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 1

// Release signing comes from the environment, never from a committed file. CI decodes the
// keystore from a secret and exports ANDROID_KEYSTORE_PATH; when it is absent (every local
// build) no release signingConfig is registered at all and `assembleRelease` stays unsigned.
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")

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
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Only wired when CI supplied a keystore; otherwise the block above created no
            // "release" config and getByName would throw.
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // No separate targetSdk override here (unlike :data/:formats): this is an application
        // module, so defaultConfig.targetSdk (36) already applies to Robolectric directly —
        // testOptions.targetSdk is only settable (and only needed) for library modules.
        unitTests {
            // LibraryActivityTest's Robolectric coverage inflates menu_library.xml and builds
            // real Views (Toolbar, RecyclerView) — needs real resource resolution, not stubs.
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
    implementation(project(":formats"))
    // Task 5 wires the seam: :data owns LibraryIndexer/BookDao, :app supplies the
    // EPUB-backed MetadataExtractor that composes it with :formats. :data declares Room
    // as `implementation`, not `api`, so :app also needs room-runtime directly below to
    // call Room.databaseBuilder(...) itself.
    implementation(project(":data"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx) // lifecycleScope in ReaderActivity
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview) // grid only: Views, no Compose
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
}
