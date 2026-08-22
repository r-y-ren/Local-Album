import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.AppExtension
import com.google.devtools.ksp.gradle.KspExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

apply(plugin = "com.android.application")
apply(plugin = "org.jetbrains.kotlin.android")
apply(plugin = "com.google.devtools.ksp")

// ── 签名配置 ──
// 从项目根目录的 keystore.properties 读取签名信息（文件不入版本控制，见 .gitignore）。
// 缺失时 release 构建产出未签名 APK（可用于 CI 编译验证）；提供后产出可安装正式包。
// 模板见根目录 keystore.properties.example。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = java.util.Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

extensions.configure<ApplicationExtension>("android") {
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

        // 构建信息写入 BuildConfig，便于正式包追溯（BuildConfig.BUILD_TIME / GIT_HASH）
        buildConfigField(
            "String",
            "BUILD_TIME",
            "\"${java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(java.util.Date())}\""
        )
        buildConfigField(
            "String",
            "GIT_HASH",
            "\"${runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short", "HEAD"))
                p.waitFor()
                p.inputStream.bufferedReader().readText().trim()
            }.getOrDefault("unknown")}\""
        )
    }

    // ── 签名配置 ──
    // 正式包签名：通过 keystore.properties 注入（见根目录 keystore.properties.example）。
    // 启用 V1/V2/V3 签名方案，保证全 Android 版本兼容。
    signingConfigs {
        create("release") {
            keystoreProperties["storeFile"]?.let { storeFile = file(it as String) }
            keystoreProperties["storePassword"]?.let { storePassword = it as String }
            keystoreProperties["keyAlias"]?.let { keyAlias = it as String }
            keystoreProperties["keyPassword"]?.let { keyPassword = it as String }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
        }
        create("lite") {
            dimension = "edition"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            resValue("string", "app_name", "LocalAlbum Lite")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 仅在提供签名密钥时绑定，避免无密钥环境（CI）构建失败
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    // 冷启动不加载；NativeAiRuntime 在首次 native AI 对象创建前按需保证 shim-first。
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

    sourceSets {
        getByName("androidTest").assets.srcDir(file("schemas"))
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

extensions.configure<KspExtension>("ksp") {
    arg("room.schemaLocation", file("schemas").path)
    arg("room.incremental", "true")
}

// ── APK 输出命名 ──
// 正式包：LocalAlbum-v0.1.0-c1-release.apk
// 调试包：LocalAlbum-v0.1.0-debug-debug.apk
// applicationVariants 在 AppExtension（内部 API）上，不在 ApplicationExtension（公共 DSL）上
the<AppExtension>().applicationVariants.all {
    val variant = this
    outputs.all {
        (this as com.android.build.gradle.internal.api.ApkVariantOutputImpl).outputFileName =
            "LocalAlbum-v${variant.versionName}-c${variant.versionCode}-${variant.name}.apk"
    }
}

// kotlinOptions 通过 KotlinCompile 任务配置（apply 方式下无 kotlinOptions 访问器）
tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 依赖坐标统一由 gradle/libs.versions.toml（Version Catalog）管理；
    // 版本与迁移前字符串声明完全一致，未夹带任何升级。
    // 注：本模块以 apply(plugin=) 方式应用插件，dependencies 块无 implementation 等类型安全访问器，
    // 故统一保留 add("configuration", libs.xxx) 写法。

    // OpenCV — 仿射变换 + 泊松融合（换脸 Reactor 流水线）
    add("implementation", project(":opencv"))

    val composeBom = platform(libs.androidx.compose.bom)

    add("implementation", libs.androidx.core.ktx)
    add("implementation", libs.androidx.lifecycle.runtime.ktx)
    add("implementation", libs.androidx.lifecycle.viewmodel.compose)
    add("implementation", libs.androidx.lifecycle.runtime.compose)
    add("implementation", libs.androidx.activity.compose)

    add("implementation", composeBom)
    add("androidTestImplementation", composeBom)

    add("implementation", libs.androidx.compose.ui)
    add("implementation", libs.androidx.compose.ui.tooling.preview)
    add("implementation", libs.androidx.compose.material.icons.extended)
    add("implementation", libs.androidx.compose.material3)
    add("implementation", libs.google.material)

    add("implementation", libs.androidx.datastore.preferences)
    add("implementation", libs.androidx.exifinterface)
    add("implementation", libs.kotlinx.coroutines.android)

    // Coil for image loading (含视频帧解码，使视频预览图可直接从视频文件提取)
    add("implementation", libs.coil.compose)
    add("implementation", libs.coil.video)

    // Media3 for video playback
    add("implementation", libs.androidx.media3.exoplayer)
    add("implementation", libs.androidx.media3.ui)
    add("implementation", libs.androidx.media3.common)

    // Room
    add("implementation", libs.androidx.room.runtime)
    add("implementation", libs.androidx.room.ktx)
    add("implementation", libs.androidx.room.paging)
    add("ksp", libs.androidx.room.compiler)
    add("androidTestImplementation", libs.androidx.room.testing)

    // WorkManager
    add("implementation", libs.androidx.work.runtime.ktx)

    // Paging 3
    add("implementation", libs.androidx.paging.runtime.ktx)
    add("implementation", libs.androidx.paging.compose)

    // ML Kit Text Recognition (OCR) — Full-only；Lite v1 无自动或手动 OCR 能力。
    add("fullImplementation", libs.mlkit.text.recognition)
    add("fullImplementation", libs.mlkit.text.recognition.chinese)

    // ML Kit Face Detection (人脸聚类 + Lite 交互式换脸的人脸 Provider)
    add("implementation", libs.mlkit.face.detection)

    // TensorFlow Lite — 设备端 ML 推理（项目仅使用核心 Interpreter API）
    add("implementation", libs.tensorflow.lite)

    // ONNX Runtime — 升级到 1.19.2 以支持 EVA02-CLIP 模型所需的 ArgMax(13) 等算子
    add("implementation", libs.onnxruntime.android)

    // PyTorch Mobile Lite — 插件模型运行时（Phase 2.3，支持 .ptl 格式模型）
    add("implementation", libs.pytorch.android.lite)

    // Core Library Desugaring
    add("coreLibraryDesugaring", libs.desugar.jdk.libs)

    add("testImplementation", libs.junit)
    add("testImplementation", libs.kotlin.test)
    add("testImplementation", libs.json)
    add("testImplementation", libs.mockito.core)
    add("testImplementation", libs.opencv.desktop)

    add("androidTestImplementation", libs.androidx.test.ext.junit)

    add("debugImplementation", libs.androidx.compose.ui.tooling)
}
