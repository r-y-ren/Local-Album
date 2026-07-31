package com.renyxin.localalbum.core.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 插件加载器（Phase 2.1）。
 *
 * 使用 [DexClassLoader] 在运行时动态加载外部 APK 插件包，实现真正的热插拔。
 *
 * ## 加载流程
 * 1. 扫描插件目录（`context.filesDir/plugins/`）下的 `.apk` 文件
 * 2. 对每个 APK，读取其 `assets/plugin_manifest.json` 获取入口类名
 * 3. 使用 `DexClassLoader` 加载 APK 的 dex 代码
 * 4. 通过 `Class.forName(entryClass, true, classLoader).newInstance()` 实例化插件
 * 5. 校验实例是否实现 [AiPlugin] 接口
 *
 * ## ClassLoader 隔离策略
 * 插件 APK 的 ClassLoader 以宿主 ClassLoader 为 parent，
 * 插件内的类优先从插件 APK 加载，宿主公共依赖（如 Kotlin 标准库）从 parent 加载。
 * 这避免了插件自带依赖与宿主冲突时的类加载问题。
 *
 * ## 安全校验
 * - 插件 APK 必须包含 `assets/plugin_manifest.json`
 * - entryClass 必须实现 [AiPlugin] 接口
 * - 可选：签名验证（通过 [verifySignature] 扩展）
 *
 * @param context 宿主 Context
 */
open class PluginLoader(private val context: Context? = null) {

    companion object {
        private const val TAG = "PluginLoader"

        /** 插件目录名（位于 context.filesDir 下） */
        const val PLUGIN_DIR = "plugins"

        /** 插件清单文件在 APK assets 中的路径 */
        const val MANIFEST_ASSET_PATH = "plugin_manifest.json"

        /** optimizedDex 输出目录名 */
        private const val OPT_DEX_DIR = "opt_dex"
    }

    /**
     * Phase 2.5 质量修复：防止 [loadAll] 并发重入。
     *
     * 使用 [Mutex] 确保同一时间只有一个加载任务执行。
     * 与 [AtomicBoolean] 不同，Mutex 会挂起等待而非直接失败，
     * 避免调用方（如 UI）误以为"没有插件"。
     */
    private val loadMutex = Mutex()

    /**
     * 单个插件的加载结果。
     *
     * @param pluginId 插件 ID
     * @param plugin 插件实例（成功时）
     * @param manifest 插件清单
     * @param apkPath APK 文件路径
     * @param error 加载失败时的错误信息（失败时 plugin 为 null）
     */
    data class LoadResult(
        val pluginId: String,
        val plugin: AiPlugin?,
        val manifest: PluginManifest?,
        val apkPath: String,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = plugin != null && error == null
    }

    /**
     * 扫描插件目录，加载所有 `.apk` 插件。
     *
     * 使用 [withContext(Dispatchers.IO)] 确保文件 I/O 和 DexClassLoader
     * 在后台线程执行，避免阻塞主线程。
     *
     * 使用 [Mutex] 排队机制：并发调用时会挂起等待前一个加载任务完成，
     * 而非直接返回空列表导致调用方误判。
     *
     * @return 每个插件的加载结果列表
     */
    open suspend fun loadAll(): List<LoadResult> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            Log.d(TAG, "loadAll 获取加载锁，开始扫描…")
            doLoadAll()
        }
    }

    private fun doLoadAll(): List<LoadResult> {
        val pluginDir = getPluginDir()
        if (!pluginDir.exists()) {
            pluginDir.mkdirs()
            Log.i(TAG, "插件目录不存在，已创建: ${pluginDir.absolutePath}")
            return emptyList()
        }

        val apkFiles = pluginDir.listFiles { file ->
            file.isFile && file.extension.equals("apk", ignoreCase = true)
        }?.sortedBy { it.name } ?: emptyList()

        if (apkFiles.isEmpty()) {
            Log.i(TAG, "插件目录无 APK 文件: ${pluginDir.absolutePath}")
            return emptyList()
        }

        Log.i(TAG, "发现 ${apkFiles.size} 个插件 APK，开始加载…")
        return apkFiles.map { apk -> loadInternal(apk) }
    }

    /**
     * 加载单个 APK 插件。
     *
     * @param apkFile APK 文件
     * @return 加载结果
     */
    open suspend fun load(apkFile: File): LoadResult = withContext(Dispatchers.IO) {
        loadInternal(apkFile)
    }

    /**
     * 同步加载逻辑（已在 [Dispatchers.IO] 上执行）。
     */
    private fun loadInternal(apkFile: File): LoadResult {
        val apkPath = apkFile.absolutePath
        Log.i(TAG, "开始加载插件: ${apkFile.name}")

        // 1. 读取插件清单
        val manifest = try {
            readManifestFromApk(apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "读取插件清单失败: ${apkFile.name}", e)
            return LoadResult(
                pluginId = apkFile.nameWithoutExtension,
                plugin = null,
                manifest = null,
                apkPath = apkPath,
                error = "读取清单失败: ${e.message}",
            )
        }

        // 2. 校验清单
        val validationError = PluginJsonCodec.validateManifest(manifest)
        if (validationError != null) {
            Log.e(TAG, "插件清单校验失败: ${apkFile.name} — $validationError")
            return LoadResult(
                pluginId = manifest.pluginId,
                plugin = null,
                manifest = manifest,
                apkPath = apkPath,
                error = "清单校验失败: $validationError",
            )
        }

        // 2.5 签名验证
        val signatureResult = verifySignature(apkFile, manifest)
        if (signatureResult != null) {
            Log.e(TAG, "插件签名验证失败: ${apkFile.name} — $signatureResult")
            return LoadResult(
                pluginId = manifest.pluginId,
                plugin = null,
                manifest = manifest,
                apkPath = apkPath,
                error = "签名验证失败: $signatureResult",
            )
        }

        // 3. 创建 DexClassLoader
        val classLoader = try {
            createDexClassLoader(apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "创建 DexClassLoader 失败: ${apkFile.name}", e)
            return LoadResult(
                pluginId = manifest.pluginId,
                plugin = null,
                manifest = manifest,
                apkPath = apkPath,
                error = "ClassLoader 创建失败: ${e.message}",
            )
        }

        // 4. 实例化插件入口类
        val plugin = try {
            val clazz = Class.forName(manifest.entryClass, true, classLoader)
            val instance = clazz.getDeclaredConstructor().newInstance()
            if (instance !is AiPlugin) {
                throw ClassCastException(
                    "入口类 ${manifest.entryClass} 未实现 AiPlugin 接口"
                )
            }
            instance
        } catch (e: Exception) {
            Log.e(TAG, "实例化插件入口类失败: ${apkFile.name} — ${manifest.entryClass}", e)
            return LoadResult(
                pluginId = manifest.pluginId,
                plugin = null,
                manifest = manifest,
                apkPath = apkPath,
                error = "入口类实例化失败: ${e.message}",
            )
        }

        Log.i(TAG, "插件加载成功: ${manifest.pluginId} (${manifest.name})")
        return LoadResult(
            pluginId = manifest.pluginId,
            plugin = plugin,
            manifest = manifest,
            apkPath = apkPath,
        )
    }

    /**
     * 从 APK 的 assets 中读取 `plugin_manifest.json`。
     */
    private fun readManifestFromApk(apkFile: File): PluginManifest {
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry("assets/$MANIFEST_ASSET_PATH")
                ?: zip.getEntry(MANIFEST_ASSET_PATH)
                ?: throw IllegalStateException("APK 中未找到 $MANIFEST_ASSET_PATH")

            zip.getInputStream(entry).use { input ->
                val json = input.bufferedReader().readText()
                return PluginJsonCodec.manifestFromJson(json)
            }
        }
    }

    /**
     * 为插件 APK 创建 [DexClassLoader]。
     *
     * @param apkFile 插件 APK 文件
     * @return 可加载插件代码的 ClassLoader
     */
    private fun createDexClassLoader(apkFile: File): DexClassLoader {
        val pluginContext = requireNotNull(context) { "PluginLoader 尚未初始化" }
        val optDexDir = File(pluginContext.filesDir, OPT_DEX_DIR)
        if (!optDexDir.exists()) optDexDir.mkdirs()

        // 使用宿主 ClassLoader 作为 parent，使插件可访问宿主公共类（如 AiPlugin 接口）
        val parentClassLoader = pluginContext.classLoader
        val optDexPath = File(optDexDir, "${apkFile.nameWithoutExtension}.odex").absolutePath

        return DexClassLoader(
            apkFile.absolutePath,
            optDexPath,
            null, // so 库搜索路径（null 表示使用默认）
            parentClassLoader,
        )
    }

    /**
     * 获取插件目录。
     */
    fun getPluginDir(): File {
        return File(context!!.filesDir, PLUGIN_DIR)
    }

    /**
     * 将一个外部 APK 文件复制到插件目录。
     *
     * 用于从 SAF Uri 或其他来源导入插件 APK。
     *
     * @param sourceFile 源 APK 文件
     * @param targetName 目标文件名（不含路径）
     * @return 插件目录中的目标文件
     */
    fun copyToPluginDir(sourceFile: File, targetName: String): File {
        val pluginDir = getPluginDir()
        if (!pluginDir.exists()) pluginDir.mkdirs()
        val target = File(pluginDir, targetName)
        sourceFile.inputStream().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        Log.i(TAG, "插件 APK 已复制到: ${target.absolutePath}")
        return target
    }

    /**
     * 从插件目录删除指定插件 APK。
     *
     * @param apkFileName APK 文件名
     * @return 是否删除成功
     */
    fun deleteFromPluginDir(apkFileName: String): Boolean {
        val file = File(getPluginDir(), apkFileName)
        val deleted = file.delete()
        if (deleted) {
            Log.i(TAG, "插件 APK 已删除: $apkFileName")
        }
        return deleted
    }

    // ---- 安全机制 ----

    /**
     * 验证插件 APK 的数字签名。
     *
     * 通过 [android.content.pm.PackageManager.getPackageArchiveInfo] 获取 APK 签名证书，
     * 计算 SHA-256 指纹并与 [PluginManifest.authorizedCertificateFingerprint] 中声明的
     * 开发者证书指纹对比。
     *
     * ## 验证策略
     * - 若 manifest 未声明 `authorizedCertificateFingerprint`，则跳过签名验证（兼容旧清单）
     * - 若 manifest 声明了证书指纹，则提取 APK 签名证书 SHA-256 进行严格匹配
     * - 在 Android API < 28 的设备上，使用已废弃的 `GET_SIGNATURES` 标志获取签名信息
     *
     * @param apkFile 插件 APK 文件
     * @param manifest 插件清单
     * @return null 表示验证通过，否则返回错误描述
     */
    private fun verifySignature(apkFile: File, manifest: PluginManifest): String? {
        val expectedFingerprint = manifest.authorizedCertificateFingerprint
        if (expectedFingerprint.isNullOrBlank()) {
            // 清单未声明证书指纹，跳过签名验证
            return null
        }

        return try {
            val pm = context!!.packageManager
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

            val packageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: return "无法读取 APK 签名信息"

            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners?.toList()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures?.toList()
            }

            if (signatures.isNullOrEmpty()) {
                return "APK 无数字签名"
            }

            // 计算每个签名证书的 SHA-256 指纹
            val fingerprints = signatures.map { sig ->
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(sig.toByteArray())
                digest.joinToString("") { "%02x".format(it) }
            }

            // 检查是否有任一签名匹配
            val matched = fingerprints.any { fp ->
                fp.equals(expectedFingerprint, ignoreCase = true)
            }

            if (!matched) {
                Log.w(TAG, "签名验证失败: 期望=$expectedFingerprint, 实际=${fingerprints.joinToString()}")
                return "签名证书指纹不匹配"
            }

            Log.d(TAG, "签名验证通过: ${apkFile.name}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "签名验证异常: ${apkFile.name}", e)
            "签名验证异常: ${e.message}"
        }
    }
}