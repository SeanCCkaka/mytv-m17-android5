plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lizongying.mytv0"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lizongying.mytv0.m17compat"
        minSdk = 22
        targetSdk = 34
        versionCode = 3
        versionName = "0.1.2-m17-test"
        ndk { abiFilters += "armeabi-v7a" }
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        debug {
            // YunOS supplies an old com.google.common on its boot class path. R8 must
            // isolate the bundled version even in the signed, updateable debug APK.
            // AGP 8.5 disables name obfuscation for debuggable builds. Retain this
            // variant's existing debug signing config, but permit actual relocation.
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // Flag to enable support for the new language APIs
        // For AGP 4.1+
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // For AGP 7.4+
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.media3.ui)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.datasource.rtmp)

    implementation(libs.nanohttpd)
    implementation(libs.gua64)
    implementation(libs.zxing)
    implementation(libs.glide)

    implementation(libs.gson)
    implementation(libs.okhttp)

    implementation(libs.core.ktx)
    implementation(libs.coroutines)

    implementation(libs.constraintlayout)
    implementation(libs.appcompat)
    implementation(libs.recyclerview)
    implementation(libs.lifecycle.viewmodel)

    implementation("org.videolan.android:libvlc-all:3.6.5")
    testImplementation("junit:junit:4.13.2")
}
