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
    implementation ("com.google.mlkit:face-detection:16.1.7")
    //implementation ("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")

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
    
    // OpenCV - Updated to 4.12.0 to fix 16KB memory page error
    implementation("org.opencv:opencv:4.12.0")





    // Testing
    implementation(libs.androidx.junit.ktx)
    implementation(libs.androidx.monitor)
    androidTestImplementation(libs.junit.junit)
}


afterEvaluate {
    tasks.named("assembleDebug").configure {
        doLast {
            val aarFile = file("C:\\Users\\ITEMS\\AndroidStudioProjects\\Camera2TestApp\\app\\build\\outputs\\aar\\app-debug.aar")
            val targetDir = file("C:\\Users\\ITEMS\\Desktop\\eye-rehab-master\\Assets\\Plugins\\Android")

            if (aarFile.exists()) {
                copy {
                    from(aarFile)
                    into(targetDir)
                    rename { "Camera2Plugin.aar" }
                }
                println("✅ AAR copied to target project at: $targetDir")
            } else {
                println("⚠️ AAR file not found: $aarFile")
            }
        }
    }
}


