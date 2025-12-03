plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt") // Glide용 어노테이션 프로세서
}

android {
    namespace = "com.example.gomofrancamera"
    compileSdk = 36 // (참고: 최신 SDK입니다. 에러 발생 시 34 또는 35로 낮추세요)

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

    // 🔴 [필수 추가] TFLite 및 Task 파일 압축 방지 설정 🔴
    // 이 설정이 없으면 앱 실행 시 모델을 읽어오다 에러가 발생합니다.
    aaptOptions {
        noCompress("tflite", "task")
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

    // 🔴 AI 분석 라이브러리 (TFLite & MediaPipe) 🔴
    // 1. 배경 인식 (ImageClassifier)
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    // 2. 이미지 데이터 처리 (TensorImage 등 사용 시 필수)
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    // 3. GPU 가속 (성능 향상)
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    // 4. 포즈 인식 (MediaPipe)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // CameraX 라이브러리
    // (libs.versions.toml 파일에 camerax 버전이 정의되어 있어야 합니다)
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