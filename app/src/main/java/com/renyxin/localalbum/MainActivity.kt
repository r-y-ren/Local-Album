package com.renyxin.localalbum

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.renyxin.localalbum.ui.LocalAlbumApp
import com.renyxin.localalbum.ui.theme.LocalAlbumTheme
import com.renyxin.localalbum.ui.theme.ThemeMode
import com.renyxin.localalbum.ui.vm.AlbumViewModel
import com.renyxin.localalbum.ui.vm.PluginViewModel
import com.renyxin.localalbum.ui.vm.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val container by lazy {
        (application as LocalAlbumApplication).container
    }

    private val albumViewModel: AlbumViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AlbumViewModel(
                    repository = container.albumRepository,
                    application = application,
                    progressManager = container.pluginAnalysisPipeline.progressManager,
                ) as T
        }
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(container.settingsRepository) as T
        }
    }

    private val pluginViewModel: PluginViewModel by viewModels {
        PluginViewModel.Factory(
            appContext = application,
            database = container.database,
            capabilityRegistry = container.capabilityRegistry,
            extensionRegistry = container.extensionPluginRegistry,
            modelManager = container.modelManager,
            modelStorageManager = container.modelStorageManager,
            modelDownloadManager = com.renyxin.localalbum.core.plugin.model.ModelDownloadManagerV2(application),
            modelCatalog = container.modelCatalog,
        )
    }

    private var hasPermission = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 权限检查必须在 onCreate 中进行，此时 Activity 上下文已就绪
        hasPermission.value = checkPermission()
        setContent {
            val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
            val themeMode = when (settingsState.themeMode) {
                1 -> ThemeMode.Light
                2 -> ThemeMode.Dark
                else -> ThemeMode.System
            }
            LocalAlbumTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val granted by hasPermission
                    if (!granted) {
                        PermissionGate(onRequest = { requestPermission() })
                    } else {
                        LocalAlbumApp(
                            albumViewModel = albumViewModel,
                            settingsViewModel = settingsViewModel,
                            pluginViewModel = pluginViewModel,
                            // 修复：pluginAnalysisPipeline 已为非空单例，去除多余的 ?.；
                            // 此处与 HybridIndexer、AlbumViewModel 共用同一管线的 progressManager。
                            progressManager = container.pluginAnalysisPipeline.progressManager,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasPermission.value = checkPermission()
    }

    /**
     * 权限检查策略：
     * - Android 13+ (API 33+): 优先检查 READ_MEDIA_IMAGES + READ_MEDIA_VIDEO
     * - Android 11-12 (API 30-32): 需要 MANAGE_EXTERNAL_STORAGE
     * - Android 10 (API 29): 需要 MANAGE_EXTERNAL_STORAGE 或 READ_EXTERNAL_STORAGE
     */
    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // P0-1: 必须同时拥有图片和视频权限，否则扫描结果不完整
            // 使用 AND 替代 OR，确保两个权限都已授予
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            // Android 10-12: 检查 MANAGE_EXTERNAL_STORAGE
            Environment.isExternalStorageManager()
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: 请求细粒度媒体权限 + 通知权限（Phase 0 扫描前台服务通知）
            // 通知权限被拒时降级为仅应用内进度，不阻塞扫描功能
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ),
                REQUEST_CODE_MEDIA_PERMISSIONS,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12: 跳转系统设置页授予 MANAGE_EXTERNAL_STORAGE
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
        // Android 10 及以下不需要运行时权限 (minSdk = 29)
    }

    @Deprecated("使用 Activity Result API；保留此回调用于兼容当前权限请求流程")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_MEDIA_PERMISSIONS) {
            hasPermission.value = checkPermission()
        }
    }

    companion object {
        private const val REQUEST_CODE_MEDIA_PERMISSIONS = 1001
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    "需要“照片和视频”权限以扫描本地媒体"
                } else {
                    "需要“所有文件访问权限”以扫描本地媒体"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRequest) {
                Text("去授权")
            }
        }
    }
}
