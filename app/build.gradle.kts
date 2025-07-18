plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.r2d2.headtracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.r2d2.headtracker"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // traz tudo do suporte antigo + compat de mídia
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    // ATENÇÃO: adicione esta linha para ter MediaSessionCompat
    implementation("androidx.media:media:1.7.0")

    // USB-Serial
    implementation("com.github.mik3y:usb-serial-for-android:3.4.6")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    val filament_version = "1.49.2"
    implementation("com.google.android.filament:filament-android:$filament_version")
    implementation("com.google.android.filament:filament-utils-android:$filament_version")
    implementation("com.google.android.filament:gltfio-android:$filament_version")
    testImplementation(libs.junit)
    // … resto das deps …
}

