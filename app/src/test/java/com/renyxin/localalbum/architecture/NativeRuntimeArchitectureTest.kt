package com.renyxin.localalbum.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level guard for the lazy native-runtime and on-demand face-swap boundaries. */
class NativeRuntimeArchitectureTest {
    private val projectRoot: File by lazy {
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { current ->
            current.parentFile?.takeUnless { it == current }
        }.firstOrNull { File(it, "app/src/main/java").isDirectory }
            ?: error("无法从工作目录定位 app/src/main/java")
    }

    private val sourceRoot: File
        get() = File(projectRoot, "app/src/main/java")

    @Test
    fun `application cold start does not initialize native ai or prepare models`() {
        val application = source("com/renyxin/localalbum/LocalAlbumApplication.kt")
        val container = source("com/renyxin/localalbum/AppContainer.kt")
        val forbiddenColdStartCalls = listOf(
            "System.loadLibrary(",
            "OpenCVLoader.initLocal(",
            "OrtEnvironment.getEnvironment(",
            "NativeAiRuntime.ensure",
            "copyBundledModels(",
            "prepareBundledModels(",
            "ensureModelReady(",
        )

        forbiddenColdStartCalls.forEach { forbidden ->
            assertFalse("Application 冷启动重新引入调用: $forbidden", application.contains(forbidden))
            assertFalse("AppContainer 组合根重新引入启动期调用: $forbidden", container.contains(forbidden))
        }
    }

    @Test
    fun `inswapper initialize remains descriptor only`() {
        val plugin = source(
            "com/renyxin/localalbum/core/plugin/extension/InSwapperPlugin.kt",
        )
        val initializeBody = extractFunctionBody(
            plugin,
            "override suspend fun initialize(",
        )

        assertTrue("InSwapper initialize 必须只注册 descriptor", initializeBody.contains("registerModel("))
        assertTrue(
            "InSwapper initialize 必须发布按需加载状态",
            initializeBody.contains("OnDemandRuntimeState.AVAILABLE_NEEDS_LOAD"),
        )
        listOf(
            "ensureModelReady(",
            "prepareBundledModels(",
            "NativeAiRuntime.",
            "OrtEnvironment.getEnvironment(",
            "OpenCVLoader.initLocal(",
            "loadEmap(",
        ).forEach { forbidden ->
            assertFalse("InSwapper initialize 不得执行重型初始化: $forbidden", initializeBody.contains(forbidden))
        }
    }

    @Test
    fun `process native bootstrap APIs have exactly one source owner`() {
        val expectedOwner =
            "app/src/main/java/com/renyxin/localalbum/core/runtime/NativeAiRuntime.kt"
        val forbiddenDirectApis = listOf(
            Regex("System\\.loadLibrary\\s*\\(\\s*\"emutls_shim\""),
            Regex("OpenCVLoader\\.initLocal\\s*\\("),
            Regex("OrtEnvironment\\.getEnvironment\\s*\\("),
        )

        forbiddenDirectApis.forEach { api ->
            val owners = kotlinSources()
                .filter { api.containsMatchIn(stripComments(it.readText())) }
                .map { it.relativeTo(projectRoot).path.replace('\\', '/') }
                .toList()
            assertEquals("原生全局入口出现旁路: ${api.pattern} -> $owners", listOf(expectedOwner), owners)
        }
    }

    @Test
    fun `every direct tflite or pytorch construction has nearby shim prerequisite`() {
        val constructors = Regex("\\b(?:Interpreter\\s*\\(|(?:org\\.pytorch\\.)?Module\\.load\\s*\\()")
        val violations = mutableListOf<String>()

        kotlinSources().forEach { file ->
            val source = stripComments(file.readText())
            constructors.findAll(source).forEach { match ->
                val prefix = source.substring(
                    (match.range.first - PREREQUISITE_LOOKBACK_CHARS).coerceAtLeast(0),
                    match.range.first,
                )
                if (!prefix.contains("NativeAiRuntime.ensureNativePrerequisite()")) {
                    val line = source.take(match.range.first).count { it == '\n' } + 1
                    violations += "${file.relativeTo(projectRoot).path.replace('\\', '/')}:$line"
                }
            }
        }

        assertTrue("发现绕过 emutls 前置的 TFLite/PyTorch 构造点: $violations", violations.isEmpty())
    }

    private fun extractFunctionBody(source: String, marker: String): String {
        val markerIndex = source.indexOf(marker)
        require(markerIndex >= 0) { "未找到函数标记: $marker" }
        val openingBrace = source.indexOf('{', markerIndex)
        require(openingBrace >= 0) { "函数缺少起始花括号: $marker" }

        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openingBrace + 1, index)
                }
            }
        }
        error("函数缺少结束花括号: $marker")
    }

    private fun source(relativePath: String): String = File(sourceRoot, relativePath).readText()

    private fun kotlinSources(): Sequence<File> = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }

    private fun stripComments(source: String): String = source
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .lineSequence()
        .joinToString("\n") { it.substringBefore("//") }

    private companion object {
        const val PREREQUISITE_LOOKBACK_CHARS = 1_200
    }
}
