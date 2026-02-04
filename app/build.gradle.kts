plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "landau.smp"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "landau.smp"
        minSdk = 16
        targetSdk = 34
        versionCode = 8
        versionName = "1.7"
        resValue("string", "app_name", "Sane Media Player")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            resValue("string", "app_name", "SMP Debug")
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // 1.3 seems to be the last version that doesn't pull in the kotlin stdlib
    implementation("androidx.annotation:annotation:1.3.0")
}