package com.renyxin.localalbum.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.SwipeLeft
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.paging.compose.collectAsLazyPagingItems
import com.renyxin.localalbum.core.model.Album
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaQueryContext
import com.renyxin.localalbum.core.recommendation.Recommendation
import com.renyxin.localalbum.data.repo.ScanState
import com.renyxin.localalbum.ui.components.DirectoryPickerDialog
import com.renyxin.localalbum.ui.screens.AlbumDetailScreen
import com.renyxin.localalbum.ui.screens.AiAnalysisPreferencesScreen
import com.renyxin.localalbum.ui.screens.AnalysisPerformanceScreen
import com.renyxin.localalbum.ui.screens.MediaViewerScreen
import com.renyxin.localalbum.ui.screens.OnboardingScreen
import com.renyxin.localalbum.ui.screens.SearchScreen
import com.renyxin.localalbum.ui.screens.FacesScreen
import com.renyxin.localalbum.ui.screens.DuplicatePhotosScreen
import com.renyxin.localalbum.ui.screens.TrashScreen
import com.renyxin.localalbum.ui.screens.ModelImportWizardScreen
import com.renyxin.localalbum.ui.screens.ModelJsonEditorScreen
import com.renyxin.localalbum.ui.screens.PluginManagerScreen
import com.renyxin.localalbum.ui.screens.FaceSwapScreen
import com.renyxin.localalbum.ui.theme.LocalAlbumTheme
import com.renyxin.localalbum.ui.theme.ThemeMode
import com.renyxin.localalbum.core.pipeline.ProgressManager
import com.renyxin.localalbum.ui.components.GlobalProgressIndicator
import com.renyxin.localalbum.ui.vm.AlbumViewModel
import com.renyxin.localalbum.ui.vm.PluginViewModel
import com.renyxin.localalbum.ui.vm.SettingsViewModel
import java.time.Instant
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class NavDestination(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

// 精简为 4 个主 Tab，对齐谷歌相册等成熟相册应用的信息架构：
// 照片（时间线+快捷入口）/ 搜索 / 相册（网格）/ 设置（含回收站与备份）
private val navDestinations = listOf(
    NavDestination("照片", Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary),
    NavDestination("搜索", Icons.Filled.Search, Icons.Outlined.Search),
    NavDestination("相册", Icons.Filled.FolderSpecial, Icons.Outlined.PhotoLibrary),
    NavDestination("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
)

// 常用系统预设路径
private val presetDirectories = listOf(
    "DCIM" to "/storage/emulated/0/DCIM",
    "Pictures" to "/storage/emulated/0/Pictures",
    "Download" to "/storage/emulated/0/Download",
    "Movies" to "/storage/emulated/0/Movies",
)

sealed interface Screen {
    data object Main : Screen
    data class AlbumDetail(val album: Album, val sourceTab: Int = 0) : Screen
    data class MediaViewer(
        val initialPath: String,
        val queryContext: MediaQueryContext,
        val returnTo: Screen,
    ) : Screen
    data object Search : Screen
    data object Timeline : Screen
    data object Trash : Screen
    data object DuplicatePhotos : Screen
    data object Faces : Screen
    data object Favorites : Screen
    data object Recommendations : Screen
    data object PluginManager : Screen
    data object AnalysisPerformance : Screen
    data object AiAnalysisPreferences : Screen
    data object FaceSwap : Screen
    data object ModelImportWizard : Screen
    data class ModelJsonEditor(val pluginId: String) : Screen
    data class RecommendationDetail(val recommendation: Recommendation) : Screen
}

/* ---- 主要应用入口 ---- */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LocalAlbumApp(
    albumViewModel: AlbumViewModel,
    settingsViewModel: SettingsViewModel,
    pluginViewModel: PluginViewModel,
    progressManager: ProgressManager? = null,
) {
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
    val themeMode = remember(settingsState.themeMode) {
        when (settingsState.themeMode) {
            1 -> ThemeMode.Light
            2 -> ThemeMode.Dark
            else -> ThemeMode.System
        }
    }

    LaunchedEffect(settingsState.aiAnalysisPreferences) {
        com.renyxin.localalbum.core.analysis.AiAnalysisPreferencesRuntime.update(
            settingsState.aiAnalysisPreferences,
        )
    }

    var currentTab by remember { mutableIntStateOf(0) }
    // 使用 back stack 管理导航历史，支持正确的返回行为
    var backStack by remember { mutableStateOf(listOf<Screen>(Screen.Main)) }
    val screen by remember { derivedStateOf { backStack.lastOrNull() ?: Screen.Main } }
    val scanState by albumViewModel.scanState.collectAsStateWithLifecycle()

    // 手势引导：仅首次安装后展示一次，之后持久化不再弹出
    val gestureGuideShown by settingsViewModel.gestureGuideShown.collectAsStateWithLifecycle()
    var showSwipeGuide by remember { mutableStateOf(false) }

    // 引导期间的主题选择临时状态
    var onboardingTheme by remember { mutableStateOf(themeMode) }
    val effectiveTheme = if (settingsState.onboardingCompleted) themeMode else onboardingTheme

    // 未完成引导：显示 OnboardingScreen（仅首次安装展示，完成后持久化不再弹出）
    if (!settingsState.onboardingCompleted) {
        LocalAlbumTheme(themeMode = effectiveTheme) {
            OnboardingScreen(
                currentTheme = effectiveTheme,
                onThemeSelected = { selected ->
                    onboardingTheme = selected
                    settingsViewModel.setThemeMode(
                        when (selected) {
                            ThemeMode.Light -> 1
                            ThemeMode.Dark -> 2
                            else -> 0
                        }
                    )
                },
                onComplete = {
                    settingsViewModel.setOnboardingCompleted(true)
                },
                onSkip = {
                    settingsViewModel.setOnboardingCompleted(true)
                },
            )
        }
        return
    }

    fun navigateTo(target: Screen) {
        backStack = backStack + target
    }

    fun goBack() {
        if (backStack.size > 1) {
            backStack = backStack.dropLast(1)
        }
    }

    fun goBackTo(target: Screen) {
        // 回到指定的来源页面（弹出到该页面）
        val idx = backStack.indexOfLast { it.javaClass == target.javaClass }
        if (idx >= 0) {
            backStack = backStack.take(idx + 1)
        } else {
            // 若未在栈中找到（比如来源是 Main 但栈发生了变化），回退到 Main
            backStack = listOf(Screen.Main)
        }
    }

    // 拦截系统返回手势：在非主页面时返回上一级，而非直接退出应用
    BackHandler(enabled = backStack.size > 1) {
        goBack()
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            (slideInHorizontally { it } + fadeIn()) togetherWith
                (slideOutHorizontally { -it } + fadeOut())
        },
        label = "screen_transition",
    ) { s ->
        when (s) {
        is Screen.AlbumDetail -> {
            AlbumDetailScreen(
                album = s.album,
                mediaPaging = albumViewModel::pagedMediaForDirectory,
                onBack = { goBack() },
                onMediaClick = { item, directoryQuery ->
                    // 传递相册当前的目录、筛选及排序条件，使查看器能够重建完整媒体序列；
                    // initialPath 仍是定位点击项的唯一权威，Repository 会据此计算 initialKey。
                    navigateTo(Screen.MediaViewer(
                        initialPath = item.filePath,
                        queryContext = MediaQueryContext.Directory(directoryQuery),
                        returnTo = s,
                    ))
                },
                onDeleteMediaItems = { paths ->
                    albumViewModel.deleteMediaItems(paths)
                },
                onBatchSetFavorite = { paths, fav ->
                    albumViewModel.batchSetFavorite(paths, fav)
                },
                onSetCover = { path ->
                    albumViewModel.updateAlbumCover(s.album.id, path)
                },
            )
        }

        is Screen.MediaViewer -> {
            // 手势引导：仅首次安装后展示一次，之后持久化不再弹出
            LaunchedEffect(gestureGuideShown) {
                if (!gestureGuideShown) {
                    showSwipeGuide = true
                    // 立即持久化标记，确保只展示一次
                    settingsViewModel.setGestureGuideShown(true)
                }
            }
            val viewerPaging = remember(s.queryContext, s.initialPath) {
                albumViewModel.viewerMedia(s.queryContext, s.initialPath)
            }
            Box(modifier = Modifier.fillMaxSize()) {
                MediaViewerScreen(
                    mediaPaging = viewerPaging,
                    initialPath = s.initialPath,
                    onBack = {
                        goBackTo(s.returnTo)
                    },
                    onDeleteItem = { path ->
                        albumViewModel.deleteMediaItems(listOf(path))
                    },
                    onToggleFavorite = { path ->
                        albumViewModel.toggleFavorite(path)
                    },
                    resolvePreview = albumViewModel::resolvePreviewThumbnail,
                )

                // 手势引导浮层（4秒后自动消失）
                AnimatedVisibility(
                    visible = showSwipeGuide,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    GestureGuideOverlay(
                        onDismiss = { showSwipeGuide = false },
                    )
                }

                // 4 秒后自动关闭引导
                if (showSwipeGuide) {
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(4000L)
                        showSwipeGuide = false
                    }
                }
            }
        }

        is Screen.Timeline -> {
            goBack()
        }

        is Screen.Trash -> {
            val trashedItems = albumViewModel.pagedTrash.collectAsLazyPagingItems()
            TrashScreen(
                trashedItems = trashedItems,
                onBack = { goBack() },
                onRestore = { paths -> albumViewModel.restoreFromTrash(paths) },
                onPermanentlyDelete = { paths -> albumViewModel.permanentlyDelete(paths) },
                onClearTrash = { albumViewModel.clearTrash() },
            )
        }

        is Screen.Favorites -> {
            val pagedFavorites = albumViewModel.pagedFavorites.collectAsLazyPagingItems()
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("收藏") },
                        navigationIcon = {
                            IconButton(onClick = { goBack() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                com.renyxin.localalbum.ui.screen.PagedTimelineScreen(
                    pagedItems = pagedFavorites,
                    onThumbnailWindowChanged = albumViewModel::requestGridThumbnails,
                    onItemClick = { item ->
                        navigateTo(
                            Screen.MediaViewer(
                                initialPath = item.filePath,
                                queryContext = MediaQueryContext.Favorites,
                                returnTo = s,
                            )
                        )
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }

        is Screen.Recommendations -> {
            RecommendationTab(
                viewModel = albumViewModel,
                onNavigateToSettings = { goBack() },
                onBack = { goBack() },
                onRecommendationClick = { recommendation ->
                    navigateTo(Screen.RecommendationDetail(recommendation))
                },
            )
        }

        is Screen.RecommendationDetail -> {
            RecommendationDetailScreen(
                recommendation = s.recommendation,
                onBack = { goBack() },
                onMediaClick = { items, index ->
                    navigateTo(Screen.MediaViewer(
                        initialPath = items[index].filePath,
                        queryContext = MediaQueryContext.StablePaths.of(items.map { it.filePath }),
                        returnTo = s,
                    ))
                },
            )
        }

        is Screen.DuplicatePhotos -> {
            val duplicateGroups by albumViewModel.duplicateGroups.collectAsStateWithLifecycle()
            val isDuplicateLoading by albumViewModel.isDuplicateLoading.collectAsStateWithLifecycle()
            val maintenanceRun by albumViewModel.duplicateMaintenanceRun.collectAsStateWithLifecycle()
            DuplicatePhotosScreen(
                duplicateGroups = duplicateGroups,
                isLoading = isDuplicateLoading,
                scannedCount = maintenanceRun?.scannedCount ?: 0,
                lastError = maintenanceRun?.error,
                onBack = { goBack() },
                onKeepOneDeleteRest = { groupId, keepPath ->
                    albumViewModel.keepOneDeleteRest(groupId, keepPath)
                },
                onRefresh = { albumViewModel.loadDuplicateGroups() },
            )
        }

        is Screen.Faces -> {
            val faceClusters by albumViewModel.faceClusters.collectAsStateWithLifecycle()
            val faceStats by albumViewModel.faceStats.collectAsStateWithLifecycle()
            val faceProgress by albumViewModel.faceFileProgress.collectAsStateWithLifecycle()
            val isPipelineRunning by albumViewModel.isPipelineRunning.collectAsStateWithLifecycle()
            // 修复：扫描完成后自动刷新人脸聚类，无需退出重进页面
            var wasScanning by remember { mutableStateOf(false) }
            LaunchedEffect(scanState) {
                val isScanning = scanState is ScanState.Scanning
                if (wasScanning && !isScanning) {
                    // 扫描刚结束（含人脸聚类），重新加载聚类数据
                    albumViewModel.loadFaceClusters()
                }
                wasScanning = isScanning
            }
            // 人脸阶段每批写库后即可显示新聚类，不必等待全量扫描完成。
            // 适度轮询避免为每张人脸建立 UI 查询，同时保证进度中的新结果可见。
            LaunchedEffect(isPipelineRunning) {
                while (isPipelineRunning) {
                    albumViewModel.loadFaceClusters()
                    delay(1_500L)
                }
                albumViewModel.loadFaceClusters()
            }
            FacesScreen(
                faceClusters = faceClusters,
                faceStats = faceStats,
                pagedMediaForCluster = albumViewModel::pagedMediaForFaceCluster,
                isLoading = false,
                onBack = { goBack() },
                onMediaClick = { item, clusterId ->
                    navigateTo(Screen.MediaViewer(
                        initialPath = item.filePath,
                        queryContext = MediaQueryContext.Face(clusterId),
                        returnTo = s,
                    ))
                },
                onSetName = { clusterId, name ->
                    albumViewModel.setPersonName(clusterId, name)
                },
                // 人物页刷新是用户要求的“重新开始全量 AI 扫描”，而非仅读取数据库或维护原型。
                onRefresh = { albumViewModel.forceReanalyzeAll() },
                stageFileProgress = faceProgress,
                isPipelineRunning = isPipelineRunning,
            )
        }

        is Screen.Search -> {
            val semanticResults by albumViewModel.semanticSearchResults.collectAsStateWithLifecycle()
            val isSemanticMode by albumViewModel.isSemanticMode.collectAsStateWithLifecycle()
            val semanticSearchState by albumViewModel.semanticSearchState.collectAsStateWithLifecycle()
            var cameraModels by remember { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(Unit) { cameraModels = albumViewModel.getSearchModels() }
            SearchScreen(
                pagedSearch = albumViewModel::pagedSearch,
                cameraModels = cameraModels,
                semanticSearchResults = semanticResults,
                isSemanticMode = isSemanticMode,
                semanticSearchState = semanticSearchState,
                onSemanticModeChange = { albumViewModel.setSemanticMode(it) },
                onSearch = { query ->
                    if (isSemanticMode) albumViewModel.semanticSearch(query)
                },
                onClearSearch = albumViewModel::clearSemanticSearch,
                onBack = { goBack() },
                onMediaClick = { item, query, stablePaths ->
                    navigateTo(Screen.MediaViewer(
                        initialPath = item.filePath,
                        queryContext = query?.let(MediaQueryContext::Search)
                            ?: MediaQueryContext.StablePaths.of(stablePaths.orEmpty()),
                        returnTo = s,
                    ))
                },
            )
        }

        is Screen.PluginManager -> {
            PluginManagerScreen(
                viewModel = pluginViewModel,
                onNavigateToFaceSwap = { navigateTo(Screen.FaceSwap) },
                onBack = { goBack() },
            )
        }

        is Screen.AnalysisPerformance -> {
            AnalysisPerformanceScreen(
                selectedMode = settingsState.analysisSchedulingMode,
                onModeSelected = settingsViewModel::setAnalysisSchedulingMode,
                onBack = { goBack() },
            )
        }

        is Screen.AiAnalysisPreferences -> {
            AiAnalysisPreferencesScreen(
                savedPreferences = settingsState.aiAnalysisPreferences,
                onSave = settingsViewModel::setAiAnalysisPreferences,
                onBack = { goBack() },
            )
        }

        is Screen.FaceSwap -> {
            FaceSwapScreen(
                viewModel = pluginViewModel,
                onBack = { goBack() },
            )
        }

        is Screen.ModelImportWizard -> {
            ModelImportWizardScreen(
                viewModel = pluginViewModel,
                onNavigateBack = { goBack() },
            )
        }

        is Screen.ModelJsonEditor -> {
            ModelJsonEditorScreen(
                viewModel = pluginViewModel,
                onNavigateBack = { goBack() },
            )
        }

        is Screen.Main -> {
            // Attempt to restore from DB on first idle display
            RememberRestoreEffect(albumViewModel)

            val configuration = LocalConfiguration.current
            val isTablet = configuration.screenWidthDp >= 600

            Scaffold(
                topBar = {
                    Column {
                        TopAppBar(
                            title = {
                                Text(
                                    text = navDestinations[currentTab].title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            actions = {
                                // 扫描状态快捷入口（仅在非设置页显示，避免冗余）
                                if (currentTab != 3 && scanState is ScanState.Scanning) {
                                    IconButton(onClick = { currentTab = 3 }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "扫描中")
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                        // 预留固定高度避免扫描开始/结束时布局跳动
                        Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                            if (scanState is ScanState.Scanning) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                },
                bottomBar = if (isTablet) {
                    @Composable {}
                } else {
                    @Composable {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 3.dp,
                        ) {
                            navDestinations.forEachIndexed { index, dest ->
                                val selected = currentTab == index
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { currentTab = index },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.title,
                                        )
                                    },
                                    label = { Text(dest.title) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                if (isTablet) {
                    Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                        NavigationRail(
                            modifier = Modifier.fillMaxHeight(),
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Spacer(Modifier.weight(1f))
                            navDestinations.forEachIndexed { index, dest ->
                                val selected = currentTab == index
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { currentTab = index },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.title,
                                        )
                                    },
                                    label = { Text(dest.title) },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                        }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        currentTabContent(
                            currentTab = currentTab,
                            albumViewModel = albumViewModel,
                            settingsViewModel = settingsViewModel,
                            navigateTo = ::navigateTo,
                            onSwitchTab = { currentTab = it },
                        )
                        // Phase 5.2: 全局进度指示器浮层
                        if (progressManager != null) {
                            GlobalProgressIndicator(
                                progressManager = progressManager,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    currentTabContent(
                        currentTab = currentTab,
                        albumViewModel = albumViewModel,
                        settingsViewModel = settingsViewModel,
                        navigateTo = ::navigateTo,
                        onSwitchTab = { currentTab = it },
                    )
                    // Phase 5.2: 全局进度指示器浮层
                    if (progressManager != null) {
                        GlobalProgressIndicator(
                            progressManager = progressManager,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
            }
        }
        }
    }
}

@Composable
private fun currentTabContent(
    currentTab: Int,
    albumViewModel: AlbumViewModel,
    settingsViewModel: SettingsViewModel,
    navigateTo: (Screen) -> Unit,
    onSwitchTab: (Int) -> Unit,
) {
    when (currentTab) {
        // Tab 0: 照片 — 时间线 + 顶部快捷入口卡片
        0 -> PhotosTab(
            viewModel = albumViewModel,
            onMediaClick = { item ->
                // 传递时间线查询上下文，让查看器加载相邻媒体并支持左右滑动；
                // initialPath 用于在分页窗口加载后精确定位用户点击的媒体。
                navigateTo(
                    Screen.MediaViewer(
                        initialPath = item.filePath,
                        queryContext = MediaQueryContext.Timeline,
                        returnTo = Screen.Timeline,
                    )
                )
            },
            onNavigateToSearch = { onSwitchTab(1) },
            onNavigateToFavorites = {
                navigateTo(Screen.Favorites)
            },
            onNavigateToDuplicate = {
                albumViewModel.loadDuplicateGroups()
                navigateTo(Screen.DuplicatePhotos)
            },
            onNavigateToFaces = {
                albumViewModel.loadFaceClusters()
                navigateTo(Screen.Faces)
            },
            onNavigateToRecommendations = {
                navigateTo(Screen.Recommendations)
            },
        )

        // Tab 1: 搜索
        1 -> {
            val semanticResults by albumViewModel.semanticSearchResults.collectAsStateWithLifecycle()
            val isSemanticMode by albumViewModel.isSemanticMode.collectAsStateWithLifecycle()
            val semanticSearchState by albumViewModel.semanticSearchState.collectAsStateWithLifecycle()
            var cameraModels by remember { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(Unit) { cameraModels = albumViewModel.getSearchModels() }
            SearchScreen(
                pagedSearch = albumViewModel::pagedSearch,
                cameraModels = cameraModels,
                semanticSearchResults = semanticResults,
                isSemanticMode = isSemanticMode,
                semanticSearchState = semanticSearchState,
                onSemanticModeChange = { albumViewModel.setSemanticMode(it) },
                onSearch = { query ->
                    if (isSemanticMode) albumViewModel.semanticSearch(query)
                },
                onClearSearch = albumViewModel::clearSemanticSearch,
                onBack = { onSwitchTab(0) },
                onMediaClick = { item, query, stablePaths ->
                    navigateTo(Screen.MediaViewer(
                        initialPath = item.filePath,
                        queryContext = query?.let(MediaQueryContext::Search)
                            ?: MediaQueryContext.StablePaths.of(stablePaths.orEmpty()),
                        returnTo = Screen.Search,
                    ))
                },
            )
        }

        // Tab 2: 相册 — 网格封面卡片
        2 -> AlbumsTab(
            viewModel = albumViewModel,
            onNavigateToSettings = { onSwitchTab(3) },
            onAlbumClick = { album ->
                navigateTo(Screen.AlbumDetail(album, sourceTab = 2))
            },
        )

        // Tab 3: 设置（含回收站入口）
        else -> SettingsTab(
            viewModel = settingsViewModel,
            albumViewModel = albumViewModel,
            onNavigateToTrash = { navigateTo(Screen.Trash) },
            onNavigateToRecommendations = { navigateTo(Screen.Recommendations) },
            onNavigateToPluginManager = { navigateTo(Screen.PluginManager) },
            onNavigateToAnalysisPerformance = { navigateTo(Screen.AnalysisPerformance) },
            onNavigateToAiAnalysisPreferences = { navigateTo(Screen.AiAnalysisPreferences) },
        )
    }
}

/* ---- 手势引导浮层 ---- */

@Composable
private fun GestureGuideOverlay(
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左滑图标
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.SwipeLeft,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 中间说明文字
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "左右滑动切换图片",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "双指捏合可缩放·双击快速切换缩放",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 关闭按钮
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭提示",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/* ---- 冷启动恢复 ---- */

@Composable
private fun RememberRestoreEffect(viewModel: AlbumViewModel) {
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val tree by viewModel.albumTree.collectAsStateWithLifecycle()

    var didRestore by remember { mutableStateOf(false) }

    LaunchedEffect(scanState, tree) {
        if (!didRestore && scanState == ScanState.Idle && tree.isEmpty()) {
            viewModel.restoreFromDbIfNeeded()
            didRestore = true
        }
    }
}

/* ---- 精选推荐 Tab ---- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecommendationTab(
    viewModel: AlbumViewModel,
    onNavigateToSettings: () -> Unit,
    onRecommendationClick: (Recommendation) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val recommendations by viewModel.recommendations.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()

    // 进入精选页立即读取当前数据库候选；扫描期间定时刷新，使已写入的媒体逐步呈现。
    LaunchedEffect(Unit) {
        viewModel.refreshRecommendations()
    }
    LaunchedEffect(scanState) {
        while (scanState is ScanState.Scanning) {
            delay(1_500L)
            viewModel.refreshRecommendations()
        }
        if (scanState is ScanState.Done) viewModel.refreshRecommendations()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = {
                        Text(
                            text = "精选推荐",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.refreshRecommendations() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "刷新推荐",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
    ) { padding ->
        // 扫描中若已有候选则继续显示内容，仅在首批结果尚未写入时展示加载态。
        if (scanState is ScanState.Scanning && recommendations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "${(scanState as ScanState.Scanning).message}\n已扫描内容将自动显示",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            return@Scaffold
        }

        if (recommendations.isEmpty()) {
            EmptyStateView(
                icon = Icons.Outlined.AutoAwesome,
                title = "暂无推荐精选",
                description = if (scanState is ScanState.Idle) "尚未进行全量媒体扫描，请先在设置中添加扫描根目录。"
                else "当前选定的扫描目录下暂未匹配到符合推荐条件的相册。",
                buttonText = "前往添加扫描目录",
                onButtonClick = onNavigateToSettings,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(recommendations, key = { "${it.albumId}-${it.windowStart}" }) { rec ->
                RecommendationCard(
                    rec = rec,
                    onClick = { onRecommendationClick(rec) },
                )
            }
        }
    }
}

/**
 * 精选推荐详情页（Phase C）。
 *
 * 点击推荐卡片后进入此页面，以缩略图网格展示该推荐中的所有媒体项，
 * 用户可从中选择要预览的具体项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecommendationDetailScreen(
    recommendation: Recommendation,
    onBack: () -> Unit,
    onMediaClick: (List<MediaItem>, Int) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = recommendation.albumName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = recommendation.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (recommendation.mediaItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("该推荐没有媒体项", style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }

        val items = recommendation.mediaItems
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items.size, key = { index -> items[index].filePath }) { index ->
                val item = items[index]
                val context = androidx.compose.ui.platform.LocalContext.current
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onMediaClick(items, index) },
                ) {
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(context)
                            .data(android.net.Uri.parse("file://${item.thumbnailPath ?: item.filePath}"))
                            .crossfade(true)
                            .size(256)
                            .build(),
                        contentDescription = item.fileName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    if (item.type == com.renyxin.localalbum.core.model.MediaType.VIDEO) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(4.dp)
                                .background(
                                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                                    RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "▶",
                                color = androidx.compose.ui.graphics.Color.White,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(
    rec: Recommendation,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            // 2×2 缩略图预览
            RecommendationThumbnailGrid(
                thumbnailPaths = rec.thumbnailPaths,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = rec.albumName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // Phase 5: 推荐类别标签
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                                ) {
                                    Text(
                                        text = rec.category.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                                Text(
                                    text = rec.directoryPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = "${rec.mediaItems.size} 张",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                        // Phase 5: 收藏数展示
                        if (rec.favoriteCount > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "${rec.favoriteCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                Text(
                    text = rec.reason,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Text(
                    text = "时间跨度: ${formatInstant(rec.windowStart)} ~ ${formatInstant(rec.windowEnd)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * 推荐卡片 2×2 缩略图预览网格。
 * 展示推荐集合中前 4 张媒体文件的缩略图，让用户对推荐内容有直观了解。
 */
@Composable
private fun RecommendationThumbnailGrid(
    thumbnailPaths: List<String>,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (thumbnailPaths.isEmpty()) {
            // 无缩略图时显示图标占位
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        } else if (thumbnailPaths.size == 1) {
            // 仅 1 张，全幅显示
            AsyncImage(
                model = coil.request.ImageRequest.Builder(context)
                    .data(android.net.Uri.parse("file://${thumbnailPaths[0]}"))
                    .crossfade(true)
                    .size(256)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (thumbnailPaths.size == 2) {
            // 2 张并排显示
            Row(modifier = Modifier.fillMaxSize()) {
                thumbnailPaths.forEach { path ->
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(context)
                            .data(android.net.Uri.parse("file://$path"))
                            .crossfade(true)
                            .size(128)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        } else {
            // 3+ 张：2×2 网格
            Column(modifier = Modifier.fillMaxSize()) {
                val rows = thumbnailPaths.take(4).chunked(2)
                rows.forEach { rowPaths ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        rowPaths.forEach { path ->
                            AsyncImage(
                                model = coil.request.ImageRequest.Builder(context)
                                    .data(android.net.Uri.parse("file://$path"))
                                    .crossfade(true)
                                    .size(128)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        // 不足 2 张时填充空位
                        repeat(2 - rowPaths.size) {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            )
                        }
                    }
                }
                // 不足 2 行时填充空行
                repeat(2 - rows.size) {
                    Spacer(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                }
            }
        }
    }
}

/* ---- 相册 Tab（树形展开 + 平铺网格双视图） ---- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumsTab(
    viewModel: AlbumViewModel,
    onNavigateToSettings: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tree by viewModel.albumTree.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()

    // 视图模式：树形展开 / 平铺网格
    var isTreeView by remember { mutableStateOf(true) }

    // 将树形结构扁平化为相册列表（用于平铺网格视图）
    val flatAlbums = remember(tree) { flattenAlbums(tree) }

    if (scanState is ScanState.Scanning && tree.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在构建相册结构…", style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    if (flatAlbums.isEmpty()) {
        EmptyStateView(
            icon = Icons.Outlined.PhotoLibrary,
            title = "暂无本地相册",
            description = if (scanState is ScanState.Idle) "尚未进行目录扫描，点击下方按钮开始配置与扫描。"
            else "未在选定的扫描根目录下找到包含媒体文件的文件夹。",
            buttonText = "前往选择扫描目录",
            onButtonClick = onNavigateToSettings,
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 视图切换栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = isTreeView,
                onClick = { isTreeView = true },
                label = { Text("树形") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = !isTreeView,
                onClick = { isTreeView = false },
                label = { Text("网格") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.GridView,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }

        if (isTreeView) {
            AlbumTreeView(
                tree = tree,
                onAlbumClick = onAlbumClick,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(flatAlbums, key = { it.id }) { album ->
                    AlbumGridCard(
                        album = album,
                        onClick = { onAlbumClick(album) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/**
 * 树形展开视图：以可折叠/展开的目录树形式展示相册层级。
 *
 * - 根节点默认展开，子节点默认折叠。
 * - 每行显示展开箭头、封面缩略图、相册名、子相册数与媒体总数。
 * - 点击有子相册的行可展开/折叠；点击行主体打开相册详情。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTreeView(
    tree: List<Album>,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 展开状态映射：albumId -> 是否展开
    val expandedIds = remember { mutableStateMapOf<String, Boolean>() }

    // 首次加载时默认展开根节点
    LaunchedEffect(tree) {
        if (expandedIds.keys.isEmpty() && tree.isNotEmpty()) {
            tree.forEach { expandedIds[it.id] = true }
        }
    }

    // 根据展开状态将树扁平化为可见行列表。
    // expandedIds 是 SnapshotStateMap，在 Composable 主体内读取会被快照系统追踪，
    // 任意展开状态变化都会触发重组并重新计算 visibleRows。
    val visibleRows = buildList {
        fun collect(albums: List<Album>, depth: Int) {
            albums.forEach { album ->
                val isExpanded = expandedIds[album.id] ?: false
                add(FlatAlbumRow(album, depth, isExpanded))
                if (album.children.isNotEmpty() && isExpanded) {
                    collect(album.children, depth + 1)
                }
            }
        }
        collect(tree, 0)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(visibleRows, key = { it.album.id }) { row ->
            AlbumTreeRow(
                album = row.album,
                depth = row.depth,
                isExpanded = row.isExpanded,
                onToggleExpand = {
                    expandedIds[row.album.id] = !(expandedIds[row.album.id] ?: false)
                },
                onClick = {
                    // 有直接媒体或是叶子节点 → 打开详情；否则切换展开状态
                    if (row.album.mediaCount > 0 || row.album.isLeaf) {
                        onAlbumClick(row.album)
                    } else {
                        expandedIds[row.album.id] = !(expandedIds[row.album.id] ?: false)
                    }
                },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/**
 * 树形视图中的单行相册：展开箭头 + 封面 + 名称 + 统计 + 右箭头。
 */
@Composable
private fun AlbumTreeRow(
    album: Album,
    depth: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasChildren = album.children.isNotEmpty()
    val indent = (depth * 16).dp

    // 展开箭头旋转动画
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        label = "arrow_rotation",
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = indent, end = 8.dp, top = 2.dp, bottom = 2.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 展开/折叠箭头（仅有子相册时可点击，叶子节点显示占位）
            if (hasChildren) {
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "折叠" else "展开",
                        modifier = Modifier.rotate(arrowRotation),
                    )
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }

            // 封面缩略图
            AlbumCover(
                album = album,
                size = 48.dp,
                modifier = Modifier,
            )

            Spacer(Modifier.width(12.dp))

            // 名称 + 统计
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hasChildren) Icons.Default.FolderOpen else Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                val subText = if (hasChildren) {
                    "含 ${album.leafCount} 个子相册 · 共 ${album.totalMediaCount} 项"
                } else {
                    "${album.mediaCount} 项"
                }
                Text(
                    text = subText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 右箭头指示可点击进入
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 扁平化相册树为列表（用于网格视图），仅保留含媒体的相册并按媒体数倒序。
 */
private fun flattenAlbums(tree: List<Album>): List<Album> {
    val result = mutableListOf<Album>()
    fun collect(albums: List<Album>) {
        albums.forEach { album ->
            result.add(album)
            if (album.children.isNotEmpty()) collect(album.children)
        }
    }
    collect(tree)
    return result.filter { it.mediaCount > 0 }.sortedByDescending { it.mediaCount }
}

/**
 * 树形视图扁平化后的行数据。
 */
private data class FlatAlbumRow(
    val album: Album,
    val depth: Int,
    val isExpanded: Boolean,
)

/**
 * 相册网格卡片：2×2 封面 + 名称 + 媒体数，对齐谷歌相册的相册卡片样式。
 */
@Composable
private fun AlbumGridCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AlbumCover(
                album = album,
                size = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${album.mediaCount} 项",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 相册封面组件：自动合成 2×2 缩略图网格。
 * - 有媒体时取前 4 张路径组成 2×2 网格
 * - 不足 4 张时用已有项填充空位
 * - 无媒体时显示文件夹图标占位
 */
@Composable
private fun AlbumCover(
    album: com.renyxin.localalbum.core.model.Album,
    size: androidx.compose.ui.unit.Dp? = null,
    modifier: Modifier = Modifier,
) {
    val coverPaths = album.coverPaths
    val context = androidx.compose.ui.platform.LocalContext.current

    val baseModifier = if (size != null) {
        modifier.size(size)
    } else {
        modifier
    }

    Box(
        modifier = baseModifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (coverPaths.isEmpty()) {
            // 无媒体，显示文件夹图标
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size((size ?: 96.dp) * 0.6f),
            )
        } else if (coverPaths.size == 1) {
            // 仅 1 张，全幅显示
            AsyncImage(
                model = coil.request.ImageRequest.Builder(context)
                    .data(android.net.Uri.parse("file://${coverPaths[0]}"))
                    .crossfade(true)
                    .size(128)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 2×2 网格
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                val rows = coverPaths.chunked(2)
                rows.forEach { rowPaths ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        rowPaths.forEach { path ->
                            AsyncImage(
                                model = coil.request.ImageRequest.Builder(context)
                                    .data(android.net.Uri.parse("file://$path"))
                                    .crossfade(true)
                                    .size(64)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        // 不足 2 张时填充空位
                        repeat(2 - rowPaths.size) {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            )
                        }
                    }
                }
                // 不足 2 行时填充空行
                repeat(2 - rows.size) {
                    Spacer(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                }
            }
        }
    }
}

/* ---- 设置 Tab ---- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsTab(
    viewModel: SettingsViewModel,
    albumViewModel: AlbumViewModel,
    onNavigateToTrash: () -> Unit = {},
    onNavigateToRecommendations: () -> Unit = {},
    onNavigateToPluginManager: () -> Unit = {},
    onNavigateToAnalysisPerformance: () -> Unit = {},
    onNavigateToAiAnalysisPreferences: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scanState by albumViewModel.scanState.collectAsStateWithLifecycle()
    val trashedCount by albumViewModel.trashedCount.collectAsStateWithLifecycle()

    var showPicker by remember { mutableStateOf(false) }
    var newIgnore by remember { mutableStateOf("") }

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
        // Section 0: 更多功能入口（回收站 / 精选推荐）
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "更多功能",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    ListItem(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onNavigateToRecommendations),
                        headlineContent = { Text("精选推荐") },
                        supportingContent = { Text("查看智能推荐的相册精选") },
                        leadingContent = {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    ListItem(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onNavigateToPluginManager),
                        headlineContent = { Text("AI 插件管理") },
                        supportingContent = { Text("导入和管理 AI 模型插件") },
                        leadingContent = {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    ListItem(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onNavigateToAnalysisPerformance),
                        headlineContent = { Text("扫描与分析性能") },
                        supportingContent = { Text("按设备能力选择稳定、均衡或性能调度") },
                        leadingContent = {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    ListItem(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onNavigateToAiAnalysisPreferences),
                        headlineContent = { Text("AI 识别与结果偏好") },
                        supportingContent = { Text("调整人物归并、OCR、语义搜索和精选推荐") },
                        leadingContent = {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                        colors = ListItemDefaults.colors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    ListItem(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onNavigateToTrash),
                        headlineContent = { Text("回收站") },
                        supportingContent = {
                            Text(if (trashedCount == 0) "空" else "$trashedCount 项待清理")
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        trailingContent = {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                }
            }
        }

        // Section 1: Scan Roots
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "扫描文件夹根目录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "应用仅对在此配置的文件夹及子目录进行扫描与索引。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )

                    // Presets
                    Text(
                        text = "常用存储预设（点击快速添加）:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        presetDirectories.forEach { (label, path) ->
                            val isAdded = state.scanRoots.contains(path)
                            AssistChip(
                                onClick = {
                                    if (isAdded) viewModel.removeScanRoot(path)
                                    else viewModel.addScanRoot(path)
                                },
                                label = { Text(label) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isAdded) Icons.Default.FolderSpecial else Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (isAdded) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                                ),
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Button to open graphical directory picker
                    Button(
                        onClick = { showPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("图形化浏览并添加目录")
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Current configured roots list
                    Text(
                        text = "已添加的扫描根 (${state.scanRoots.size}):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    if (state.scanRoots.isEmpty()) {
                        Text(
                            text = "尚未添加任何目录，请从预设点击添加或通过\"图形化浏览\"选择目录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        state.scanRoots.forEach { rootPath ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = rootPath,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(onClick = { viewModel.removeScanRoot(rootPath) }) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "删除目录",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 2: Ignore Directory Names
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "忽略规则（正则）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "支持正则表达式，同时匹配目录名与文件名。命中任一规则的目录或文件将被跳过扫描。" +
                            "例如输入 ^.trash 可跳过所有以 .trash 开头的目录与文件；非法正则将被自动忽略。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newIgnore,
                            onValueChange = { newIgnore = it },
                            label = { Text("正则规则") },
                            placeholder = { Text("^.trash") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = {
                                if (newIgnore.isNotBlank()) {
                                    viewModel.addIgnoreDir(newIgnore.trim())
                                    newIgnore = ""
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("添加规则")
                        }
                    }

                    if (state.ignoreDirNames.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.ignoreDirNames.forEach { name ->
                                AssistChip(
                                    onClick = { viewModel.removeIgnoreDir(name) },
                                    label = { Text(name) },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "删除规则",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section 2.5: .nomedia 显示开关
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "显示 .nomedia 目录",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "开启后将扫描含 .nomedia 标记的目录（默认关闭）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = state.showNomediaDirectories,
                            onCheckedChange = { viewModel.setShowNomediaDirectories(it) },
                        )
                    }
                }
            }
        }

        // Section 2.6: 相册排序模式
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "相册排序方式",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val sortOptions = listOf("按名称" to 0, "按日期" to 1, "按大小" to 2, "按数量" to 3)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sortOptions.forEach { (label, mode) ->
                            FilterChip(
                                selected = state.albumSortMode == mode,
                                onClick = {
                                    viewModel.setAlbumSortMode(mode)
                                    albumViewModel.rebuildAlbumTree()
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }

        // Section 3: Scan Control Card
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "扫描操作",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Button(
                        onClick = { albumViewModel.rescan() },
                        enabled = scanState !is ScanState.Scanning && state.scanRoots.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (scanState is ScanState.Scanning) "全量扫描中…"
                            else "开始扫描媒体文件"
                        )
                    }

                    when (val s = scanState) {
                        is ScanState.Scanning -> {
                            Text(
                                text = s.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        is ScanState.Failed -> {
                            Text(
                                text = "扫描失败: ${s.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        ScanState.Done -> {
                            Text(
                                text = "扫描完成！可切换至\"相册树\"或\"精选推荐\"页浏览。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }

                        else -> {}
                    }
                }
            }
        }

        // Section 4: Backup & Restore (Phase 4.2)
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            ) {
                val backupState by albumViewModel.backupState.collectAsStateWithLifecycle()
                val pendingImport by albumViewModel.pendingImport.collectAsStateWithLifecycle()
                val context = androidx.compose.ui.platform.LocalContext.current

                // SAF 默认导出 ZIP + NDJSON；导入器仍兼容历史 JSON 文件。
                val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
                ) { uri ->
                    if (uri != null) albumViewModel.exportDatabaseToUri(context, uri)
                }
                // SAF 导入：接受新的 ZIP 备份及历史 JSON 文件。
                val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) albumViewModel.prepareImportFromUri(context, uri)
                }

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "备份与恢复",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "默认导出 ZIP 流式备份，适合大图库；仍可导入历史 JSON 备份。可选择自定义位置导出/导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )

                    // Export button
                    OutlinedButton(
                        onClick = {
                            val fileName = "localalbum_backup_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.zip"
                            exportLauncher.launch(fileName)
                        },
                        enabled = backupState !is AlbumViewModel.BackupState.InProgress,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("导出数据库（选择位置）")
                    }

                    // Import button
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*"))
                        },
                        enabled = backupState !is AlbumViewModel.BackupState.InProgress,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("导入数据库（选择文件）")
                    }

                    // Status display
                    when (val s = backupState) {
                        is AlbumViewModel.BackupState.InProgress -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = s.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        is AlbumViewModel.BackupState.Success -> {
                            Text(
                                text = s.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        is AlbumViewModel.BackupState.Error -> {
                            Text(
                                text = s.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> {}
                    }
                }

                // Import confirmation dialog（SAF 选择文件并校验后弹出）
                pendingImport?.let { (message, tempPath) ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { albumViewModel.cancelImport() },
                        title = { Text("确认导入") },
                        text = { Text(message) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    if (tempPath != null) albumViewModel.confirmImport(tempPath)
                                },
                            ) { Text("确认导入") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { albumViewModel.cancelImport() },
                            ) { Text("取消") }
                        },
                    )
                }
            }
        }
    }
}

/* ---- 空状态组件 ---- */

@Composable
private fun EmptyStateView(
    icon: ImageVector,
    title: String,
    description: String,
    buttonText: String,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(80.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onButtonClick,
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(buttonText)
            }
        }
    }
}

/* ---- 工具函数 ---- */

private fun formatInstant(instant: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}

/* ---- 照片 Tab（时间线 + 顶部快捷入口卡片） ---- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotosTab(
    viewModel: AlbumViewModel,
    onMediaClick: (MediaItem) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToDuplicate: () -> Unit,
    onNavigateToFaces: () -> Unit,
    onNavigateToRecommendations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val favoriteCount by viewModel.favoriteCount.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()

    // Paging 3 是时间线唯一的媒体数据源，禁止维护全库 List 镜像。
    val pagedItems = viewModel.pagedMedia.collectAsLazyPagingItems()

    if (scanState is ScanState.Scanning && pagedItems.itemCount == 0) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在加载时间线…", style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    if (pagedItems.itemCount == 0) {
        EmptyStateView(
            icon = Icons.Outlined.PhotoLibrary,
            title = "暂无媒体文件",
            description = "请在设置中添加扫描目录并开始扫描。",
            buttonText = "前往设置",
            onButtonClick = { /* handled by tab switch */ },
            modifier = modifier,
        )
        return
    }

    // 顶部快捷入口卡片 + 分页时间线网格
    // 注意：不能将 LazyVerticalGrid 嵌套进 LazyColumn 的 item 中（会导致运行时崩溃），
    // 因此通过 headerContent 将快捷入口卡片注入到 PagedTimelineScreen 内部的
    // LazyVerticalGrid 中，作为占满整行的 header item。
    com.renyxin.localalbum.ui.screen.PagedTimelineScreen(
        pagedItems = pagedItems,
        onItemClick = onMediaClick,
        onThumbnailWindowChanged = viewModel::requestGridThumbnails,
        modifier = modifier.fillMaxSize(),
        headerContent = {
            QuickAccessRow(
                favoritesCount = favoriteCount,
                onNavigateToSearch = onNavigateToSearch,
                onNavigateToFavorites = onNavigateToFavorites,
                onNavigateToDuplicate = onNavigateToDuplicate,
                onNavigateToFaces = onNavigateToFaces,
                onNavigateToRecommendations = onNavigateToRecommendations,
            )
        },
    )
}

/**
 * 顶部快捷入口横向卡片行，对齐谷歌相册的"回忆/收藏/实用工具"入口区。
 */
@Composable
private fun QuickAccessRow(
    favoritesCount: Int,
    onNavigateToSearch: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToDuplicate: () -> Unit,
    onNavigateToFaces: () -> Unit,
    onNavigateToRecommendations: () -> Unit,
) {
    val scrollState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.foundation.lazy.LazyRow(
        state = scrollState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "favorites") {
            QuickAccessCard(
                icon = Icons.Filled.Favorite,
                title = "收藏",
                subtitle = if (favoritesCount > 0) "$favoritesCount 张" else "查看收藏",
                onClick = onNavigateToFavorites,
            )
        }
        item(key = "recommendations") {
            QuickAccessCard(
                icon = Icons.Filled.AutoAwesome,
                title = "精选",
                subtitle = "智能推荐",
                onClick = onNavigateToRecommendations,
            )
        }
        item(key = "duplicate") {
            QuickAccessCard(
                icon = Icons.Filled.CleaningServices,
                title = "重复照片",
                subtitle = "清理重复",
                onClick = onNavigateToDuplicate,
            )
        }
        item(key = "faces") {
            QuickAccessCard(
                icon = Icons.Filled.Face,
                title = "人物",
                subtitle = "按人脸分组",
                onClick = onNavigateToFaces,
            )
        }
        item(key = "search") {
            QuickAccessCard(
                icon = Icons.Filled.Search,
                title = "搜索",
                subtitle = "关键词/语义",
                onClick = onNavigateToSearch,
            )
        }
    }
}

/**
 * 单个快捷入口卡片。
 */
@Composable
private fun QuickAccessCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 112.dp, height = 132.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/* ---- MediaEntity → MediaItem 转换扩展 ---- */

private fun com.renyxin.localalbum.data.db.entity.MediaEntity.toMediaItem(): MediaItem {
    return MediaItem(
        id = java.util.UUID.nameUUIDFromBytes(filePath.toByteArray()).toString(),
        filePath = filePath,
        fileName = fileName,
        type = mediaType,
        capturedAt = java.time.Instant.ofEpochMilli(capturedAtMs),
        modifiedAt = java.time.Instant.ofEpochMilli(modifiedAtMs),
        fileSize = fileSize,
        isFavorite = isFavorite,
        width = width,
        height = height,
        mimeType = mimeType,
        durationMs = durationMs,
        isTrashed = isTrashed,
        latitude = latitude,
        longitude = longitude,
        make = make,
        model = model,
        aperture = aperture,
        focalLength = focalLength,
        iso = iso,
        exposureTime = exposureTime,
        orientation = orientation,
        sceneType = sceneType,
        thumbnailPath = thumbnailPath,
        faceClusterId = faceClusterId,
        ocrText = ocrText,
        qualityScore = qualityScore,
    )
}
