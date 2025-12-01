plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt") // Glide를 위해 유지 (정상)
}

android {
    namespace = "com.example.gomofrancamera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.gomofrancamera"
        minSdk = 24
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

    // 기본 AndroidX 라이브러리
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // 테스트 라이브러리
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // 🔴 AI 분석 라이브러리 (libs 참조 방식 대신 직접 주소 입력 방식으로 수정) 🔴
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // CameraX 라이브러리
    implementation("androidx.camera:camera-core:${libs.versions.camerax.get()}")
    implementation("androidx.camera:camera-camera2:${libs.versions.camerax.get()}")
    implementation("androidx.camera:camera-lifecycle:${libs.versions.camerax.get()}")
    implementation("androidx.camera:camera-view:${libs.versions.camerax.get()}")

    // 기타 UI 라이브러리
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // 이미지 로딩 라이브러리 (Glide)
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")
}
