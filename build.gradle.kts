// 使用 buildscript classpath 方式声明插件，而非 plugins DSL。
// 原因：在此环境（Gradle 8.12/8.13 + Arch Linux）下，plugins DSL 会让 Kotlin Gradle Plugin
// 注入的 kotlin-stdlib:{strictly ...} 依赖约束被 Gradle 依赖解析引擎过滤掉，
// 导致子项目 build.gradle.kts 的 stage2（residual program）脚本编译 classpath
// 同时缺失 kotlin-stdlib 与 JDK 平台类，出现 "Unresolved reference: util/text/it"。
// 改用 buildscript classpath 可绕过该约束过滤问题。
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
        // 显式声明 kotlin-stdlib，防止 KGP 的 strictly 约束被过滤后
        // stage2 脚本编译 classpath 缺失 Kotlin 标准库与 JDK 平台类
        classpath("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
        classpath("org.jetbrains.kotlin:kotlin-reflect:1.9.24")
        classpath("org.jetbrains.kotlin:kotlin-script-runtime:1.9.24")
    }
}
