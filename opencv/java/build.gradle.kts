import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.tasks.compile.JavaCompile

apply(plugin = "com.android.library")

extensions.configure<LibraryExtension>("android") {
    namespace = "org.opencv"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // OpenCV Java 绑定是上游生成代码，内部为兼容旧接口会调用已弃用 API。
    // 不修改生成源码；在模块边界局部关闭该类提示，应用代码仍保留完整弃用检查。
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-Xlint:-deprecation")
        options.compilerArgs.add("-Xlint:-removal")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src")
            res.srcDirs("res")
            manifest.srcFile("AndroidManifest.xml")
        }
    }
}

dependencies {
    add("implementation", "androidx.annotation:annotation:1.8.0")
}
