package com.renyxin.localalbum.ui.screens.settings

/**
 * 设置 Tab 主体（自 LocalAlbumApp.kt 纯 cut-paste 提取并 Section 化，行为不变）。
 *
 * 职责：收集状态、宿主目录选择对话框（DirectoryPickerDialog），
 * 并按顺序编排 7 个 Section Composable（更多功能/扫描根目录/忽略规则/
 * 显示开关/排序/扫描操作/备份恢复）。各 Section 实现见同目录其余文件。
 */
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.renyxin.localalbum.edition.EditionFeatures
import com.renyxin.localalbum.ui.components.DirectoryPickerDialog
import com.renyxin.localalbum.ui.isCoreScanActive
import com.renyxin.localalbum.ui.vm.AlbumViewModel
import com.renyxin.localalbum.ui.vm.SettingsViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsTab(
    viewModel: SettingsViewModel,
    albumViewModel: AlbumViewModel,
    editionFeatures: EditionFeatures,
    onNavigateToTrash: () -> Unit = {},
    onNavigateToPluginManager: () -> Unit = {},
    onNavigateToFaceSwap: () -> Unit = {},
    onNavigateToAnalysisPerformance: () -> Unit = {},
    onNavigateToEditionDestination: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scanState by albumViewModel.scanState.collectAsStateWithLifecycle()
    val pipelineState by albumViewModel.libraryPipelineState.collectAsStateWithLifecycle()
    val indexAvailability by albumViewModel.indexAvailability.collectAsStateWithLifecycle()
    val coreScanState by albumViewModel.coreScanState.collectAsStateWithLifecycle()
    val enhancementState by albumViewModel.enhancementState.collectAsStateWithLifecycle()
    val coreScanActive = pipelineState.hasStartedOrRequestedScan ||
        isCoreScanActive(scanState, coreScanState)
    val trashedCount by albumViewModel.trashedCount.collectAsStateWithLifecycle()

    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        DirectoryPickerDialog(
            onDismissRequest = { showPicker = false },
            onDirectorySelected = { selectedPath ->
                viewModel.addScanRoot(selectedPath)
                showPicker = false
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Section 0: 更多功能入口
        item {
            MoreFeaturesSection(
                editionFeatures = editionFeatures,
                trashedCount = trashedCount,
                onNavigateToTrash = onNavigateToTrash,
                onNavigateToPluginManager = onNavigateToPluginManager,
                onNavigateToFaceSwap = onNavigateToFaceSwap,
                onNavigateToAnalysisPerformance = onNavigateToAnalysisPerformance,
                onNavigateToEditionDestination = onNavigateToEditionDestination,
            )
        }

        // Section 1: Scan Roots
        item {
            ScanRootsSection(
                scanRoots = state.scanRoots,
                viewModel = viewModel,
                onOpenPicker = { showPicker = true },
            )
        }

        // Section 2: Ignore Directory Names
        item {
            IgnoreRulesSection(
                ignoreDirNames = state.ignoreDirNames,
                viewModel = viewModel,
            )
        }

        // Section 2.5: .nomedia 显示开关
        item {
            NomediaToggleSection(
                showNomediaDirectories = state.showNomediaDirectories,
                viewModel = viewModel,
            )
        }

        // Section 2.6: 扫描识别进度 UI
        item {
            ProgressUiToggleSection(
                showAnalysisProgressUi = state.showAnalysisProgressUi,
                viewModel = viewModel,
            )
        }

        // Section 2.7: 相册排序模式
        item {
            AlbumSortSection(
                albumSortMode = state.albumSortMode,
                viewModel = viewModel,
                albumViewModel = albumViewModel,
            )
        }

        // Section 3: Scan Control Card
        item {
            ScanControlSection(
                coreScanActive = coreScanActive,
                hasScanRoots = state.scanRoots.isNotEmpty(),
                scanState = scanState,
                indexAvailability = indexAvailability,
                coreScanState = coreScanState,
                enhancementState = enhancementState,
                albumViewModel = albumViewModel,
            )
        }

        // Section 4: Backup & Restore (Phase 4.2)
        item {
            BackupRestoreSection(albumViewModel = albumViewModel)
        }
    }
}
