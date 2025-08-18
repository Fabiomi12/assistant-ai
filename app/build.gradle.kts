plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("kapt")
    alias(libs.plugins.ksp)
    alias(libs.plugins.dagger.hilt)
    kotlin("plugin.serialization") version "1.9.0"
}

android {
    namespace = "edu.upt.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "edu.upt.assistant"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Ship only the device ABI for speed/size
        ndk {
            abiFilters.clear()
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Make the native lib FAST even in debug variant
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DLLAMA_NO_MMAP=OFF",
                    "-DGGML_BLAS=OFF",
                    "-DGGML_SHARED=OFF",
                    "-DGGML_BACKEND_SHARED=OFF",
                    "-DGGML_OPENMP=OFF",        // ⬅️
                    "-DGGML_USE_OPENMP=OFF",    // ⬅️
                    "-DGGML_THREADPOOL=ON",     // ⬅️
                    "-DANDROID_STL=c++_shared"
                )
                // Optimize
                cFlags  += listOf("-O3", "-DNDEBUG")
                cppFlags += listOf("-O3", "-DNDEBUG", "-std=c++17")
            }
        }
    }

    sourceSets {
        getByName("main") { assets.srcDirs("src/main/assets") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            // ensure native Release even for debug APK
            externalNativeBuild {
                cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release") }
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk { debugSymbolLevel = "none" }
            externalNativeBuild {
                cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release") }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    buildFeatures { compose = true }
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)
    implementation(libs.kotlinx.serialization.json)
}
