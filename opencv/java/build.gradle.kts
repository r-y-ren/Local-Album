import com.android.build.api.dsl.LibraryExtension

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
