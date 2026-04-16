import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keyProps = Properties().also { props ->
    val f = rootProject.file("key.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    namespace = "com.gradesnap.omr"
    compileSdk = 35

    signingConfigs {
        create("release") {
            keyAlias     = keyProps["keyAlias"]     as String
            keyPassword  = keyProps["keyPassword"]  as String
            storeFile    = file(keyProps["storeFile"] as String)
            storePassword = keyProps["storePassword"] as String
        }
    }

    defaultConfig {
        applicationId = "com.gradesnap.omr"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    flavorDimensions += "method"
    productFlavors {
        create("traditional") {
            dimension = "method"
            applicationIdSuffix = ".traditional"
            versionNameSuffix = "-cv"
            resValue("string", "app_name", "GradeSnap CV")
        }
        create("yolo") {
            dimension = "method"
            applicationIdSuffix = ".yolo"
            versionNameSuffix = "-yolo"
            resValue("string", "app_name", "GradeSnap YOLO")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // OpenCV Android (official Maven Central package, no OpenCV Manager needed)
    implementation("org.opencv:opencv:4.9.0")

    // ONNX Runtime — yolo flavor only
    add("yoloImplementation", "com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // CameraX
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // ExifInterface - đọc EXIF để tự xoay ảnh chụp ngang
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Gson - JSON serialization for project persistence
    implementation("com.google.code.gson:gson:2.10.1")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
