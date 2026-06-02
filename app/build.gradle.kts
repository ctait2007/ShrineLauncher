plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shrine.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shrine.launcher"
        minSdk = 22
        targetSdk = 34
        versionCode = 60
        versionName = "0.10.7"
    }

    // Use the project keystore (shrine-debug.jks) when present — ensures the same
    // signature as CI builds so installs over existing versions always work.
    // Place shrine-debug.jks in the project root (gitignored). If not present,
    // Gradle falls back to the default Android debug keystore.
    val keystoreFile = rootProject.file("shrine-debug.jks")
    if (keystoreFile.exists()) {
        signingConfigs {
            create("shrine") {
                storeFile     = keystoreFile
                storePassword = "android"
                keyAlias      = "shrine"
                keyPassword   = "android"
            }
        }
    }

    buildTypes {
        debug {
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("shrine")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("shrine")
            }
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.leanback:leanback:1.2.0-alpha02")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")
}
