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
        
        // Specify NDK version for 16KB alignment
        ndkVersion = "28.0.12433566"
        
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-30"

                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Corrected JNI libs location
    sourceSets.getByName("main") {
        jniLibs.srcDirs("src/main/jniLibs")
    }
    
    // Configure packaging options for native libraries
    packaging {
        jniLibs {
            useLegacyPackaging = false
            // Keep all architectures
            pickFirsts += listOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so"
            )
        }
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
    implementation("com.google.mediapipe:tasks-vision:0.10.29")
    implementation("com.google.mediapipe:tasks-core:0.10.29")
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
    
    // OpenCV - Built from source with NDK r28+ for 16KB alignment
    // Remove the stock AAR dependency:
    // implementation("org.opencv:opencv:4.12.0")
    // Instead, we'll use the custom-built native libraries





    // Testing
    implementation(libs.androidx.junit.ktx)
    implementation(libs.androidx.monitor)
    implementation(project(":openCVLibrary412"))
    androidTestImplementation(libs.junit.junit)
}


afterEvaluate {
    tasks.named("assembleDebug").configure {
        doLast {
            val aarFile = file("C:\\Users\\ITEMS\\AndroidStudioProjects\\Camera2TestApp\\app\\build\\outputs\\aar\\app-debug.aar")
            val targetDir = file("C:\\Users\\ITEMS\\AndroidStudioProjects\\testApp\\app\\libs")

            if (aarFile.exists()) {
                copy {
                    from(aarFile)
                    into(targetDir)
                    rename { "app-debug.aar" }
                }
                println("✅ AAR copied to target project at: $targetDir")
            } else {
                println("⚠️ AAR file not found: $aarFile")
            }
        }
    }
}


