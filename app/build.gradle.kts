plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.renyxin.localalbum"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.renyxin.localalbum"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Phase 2.5 质量修复：限制 so 库架构，减小 APK 体积
        // arm64-v8a 覆盖绝大多数设备，x86_64 用于模拟器
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // emutls 统一实现 shim：编译 libemutls_shim.so，导出标准 __emutls_get_address
    // 解决 ONNX Runtime / OpenCV / TFLite / PyTorch 多 native 库 emutls 符号冲突
    // （识别扫描 / 换脸时 ONNX↔OpenCV 交替调用触发 SIGSEGV 闪退）。
    // 加载顺序见 LocalAlbumApplication.onCreate（System.loadLibrary("emutls_shim")）。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // ONNX 模型不压缩，便于 ONNX Runtime 以 mmap 方式高效加载
    androidResources {
        noCompress += "onnx"
    }

    lint {
        disable += "NewApi"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // OpenCV — 仿射变换 + 泊松融合（换脸 Reactor 流水线）
    implementation(project(":opencv"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("com.google.android.material:material:1.14.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Coil for image loading (含视频帧解码，使视频预览图可直接从视频文件提取)
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")

    // Media3 for video playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("androidx.room:room-paging:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Paging 3
    implementation("androidx.paging:paging-runtime-ktx:3.3.2")
    implementation("androidx.paging:paging-compose:3.3.2")

    // ML Kit Text Recognition (OCR)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // ML Kit Face Detection (人脸聚类)
    implementation("com.google.mlkit:face-detection:16.1.7")

    // osmdroid — 开源地图 SDK（无需 API Key，Phase 3.4 地图视图）
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // TensorFlow Lite — 设备端 ML 推理（Phase 4.1 语义搜索，Phase 2.3 插件模型运行时）
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // ONNX Runtime — 升级到 1.19.2 以支持 EVA02-CLIP 模型所需的 ArgMax(13) 等算子
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // PyTorch Mobile Lite — 插件模型运行时（Phase 2.3，支持 .ptl 格式模型）
    implementation("org.pytorch:pytorch_android_lite:1.13.1")

    // Core Library Desugaring
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("androidx.compose.runtime:runtime-livedata:1.7.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
    testImplementation("org.json:json:20240303")
    testImplementation("org.mockito:mockito-core:5.5.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}