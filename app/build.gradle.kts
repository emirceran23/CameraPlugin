plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.camera2testapp"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        targetSdk = 35

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    // Corrected JNI libs location
    sourceSets.getByName("main") {
        jniLibs.srcDirs("src/main/jniLibs")
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
}

dependencies {
    implementation("androidx.databinding:viewbinding:4.1.3")
    implementation("com.google.mediapipe:tasks-vision:0.10.20")
    implementation("com.google.mediapipe:tasks-core:0.10.20")

    // Jetpack Compose
    implementation("androidx.compose.ui:ui:1.5.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.0")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.compiler:compiler:1.5.0")
    implementation("androidx.compose.foundation:foundation:1.5.0")
    implementation("androidx.compose.material:material:1.5.0")
    implementation("androidx.compose.runtime:runtime:1.5.0")

    // AndroidX Core Libraries
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.core:core-ktx:1.12.0")

    // CameraX Dependencies
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")

    // EXIF Metadata Handling
    implementation("androidx.exifinterface:exifinterface:1.3.6")
    implementation ("com.quickbirdstudios:opencv:4.5.3.0")
    implementation ("org.tensorflow:tensorflow-lite:2.9.0")
    implementation ("org.tensorflow:tensorflow-lite-support:0.3.1") // Optional, for easier input processing
    implementation ("org.tensorflow:tensorflow-lite-task-vision:0.4.4") // For MoveNet




    // Testing
    implementation(libs.androidx.junit.ktx)
    implementation(libs.androidx.monitor)
    androidTestImplementation(libs.junit.junit)
}
