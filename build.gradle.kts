// 2026-10 工具链大版本升级：Gradle 8.13 → 9.8.2，AGP 8.13.2 → 9.5.0（内置 Kotlin），Kotlin 1.9.24 → 2.2+。
//
// AGP 9 内置 Kotlin（android.builtInKotlin 默认 true）：模块脚本不再 apply
// org.jetbrains.kotlin.android——Kotlin 编译由 AGP 内置管理；Compose 编译器插件仍需
// 显式应用（compose 开启时的硬性要求）；KSP 使用内置 Kotlin 兼容的独立版本线（2.3.x）。
//
// 历史：Kotlin 1.9 + Gradle 8.12/8.13 时代曾因 KGP 的 kotlin-stdlib 约束被过滤导致
// stage2 脚本编译失败而长期使用 buildscript classpath 绕行；Gradle 9.8 + AGP 9 下
// plugins DSL 实测正常。回退方案见 git 历史与 ARCHITECTURE §2.1。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

buildscript {
    repositories {
        google()
    }
    dependencies {
        // R8 显式钉版：AGP 9.5.0 自带 R8 对 Kotlin 2.2 suspend 函数（HybridIndexer）
        // 误编译，release 启动即 VerifyError（copy-cat type=Undefined）。classpath
        // 钉版是官方推荐的 R8 覆盖方式；升级 AGP 时若其自带队列已修复可移除。
        classpath("com.android.tools:r8:9.5.28")
    }
}
