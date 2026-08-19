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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.renyxin.localalbum.edition.EditionFeatures
import com.renyxin.localalbum.edition.EditionSearchContribution
import com.renyxin.localalbum.edition.EditionUiContribution
import com.renyxin.localalbum.data.repo.AlbumSyncState
import com.renyxin.localalbum.data.repo.ScanState
import com.renyxin.localalbum.ui.components.AlbumSyncStatusBanner
import com.renyxin.localalbum.ui.components.DirectoryPickerDialog
import com.renyxin.localalbum.ui.screens.AlbumDetailScreen
import com.renyxin.localalbum.ui.screens.MediaFilter
import com.renyxin.localalbum.ui.screens.SortMode
import com.renyxin.localalbum.ui.screens.ViewMode
import com.renyxin.localalbum.ui.screens.AnalysisPerformanceScreen
import com.renyxin.localalbum.ui.screens.MediaViewerScreen
import com.renyxin.localalbum.ui.screens.AlbumsTab
import com.renyxin.localalbum.ui.screens.GestureGuideOverlay
import com.renyxin.localalbum.ui.screens.PhotosTab
import com.renyxin.localalbum.ui.screens.RememberRestoreEffect
import com.renyxin.localalbum.ui.screens.OnboardingScreen
import com.renyxin.localalbum.ui.screens.RecommendationDetailScreen
import com.renyxin.localalbum.ui.screens.RecommendationTab
import com.renyxin.localalbum.ui.screens.SearchScreen
import com.renyxin.localalbum.ui.screens.DuplicatePhotosScreen
import com.renyxin.localalbum.ui.screens.TrashScreen
import com.renyxin.localalbum.ui.screens.ModelImportWizardScreen
import com.renyxin.localalbum.ui.screens.ModelJsonEditorScreen
import com.renyxin.localalbum.ui.screens.PluginManagerScreen
import com.renyxin.localalbum.ui.screens.settings.SettingsTab
import com.renyxin.localalbum.ui.screens.FaceSwapScreen
import com.renyxin.localalbum.ui.theme.LocalAlbumTheme
import com.renyxin.localalbum.ui.theme.ThemeMode
import com.renyxin.localalbum.core.pipeline.ProgressManager
import com.renyxin.localalbum.ui.components.GlobalProgressIndicator
import com.renyxin.localalbum.ui.vm.AlbumViewModel
import com.renyxin.localalbum.ui.vm.FaceSwapViewModel
import com.renyxin.localalbum.ui.vm.PluginViewModel
import com.renyxin.localalbum.ui.vm.SettingsViewModel

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

/** 主 Tab 索引等共享常量与工具已提取至 UiCommon.kt（同包直接引用）。 */

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
    data object Favorites : Screen
    data object Recommendations : Screen
    data object PluginManager : Screen
    data object AnalysisPerformance : Screen
    data object FaceSwap : Screen
    data class Edition(val destinationId: String) : Screen
    data object ModelImportWizard : Screen
    data class ModelJsonEditor(val pluginId: String) : Screen
    data class RecommendationDetail(val recommendation: Recommendation) : Screen
}

/**
 * edition 降级规则：当前 edition 功能开关不允许的 Screen 统一降级为 [Screen.Main]。
 *
 * 规则：插件管理/模型向导/JSON 编辑器受 [EditionFeatures.showPluginManager] 约束；
 * 换脸受 [EditionFeatures.showFaceSwap] 约束；[Screen.Edition] 仅在
 * EditionUiContribution 注册了对应 destinationId 时可达；其余 Screen 原样放行。
 * 导航宿主在入栈与渲染两处都经本函数过滤，确保降级后的返回栈行为一致。
 */
internal fun resolveEditionScreen(screen: Screen, features: EditionFeatures): Screen = when (screen) {
    Screen.PluginManager,
    Screen.ModelImportWizard,
    is Screen.ModelJsonEditor -> if (features.showPluginManager) screen else Screen.Main
    Screen.FaceSwap -> if (features.showFaceSwap) screen else Screen.Main
    is Screen.Edition -> if (EditionUiContribution.acceptsDestination(screen.destinationId)) screen else Screen.Main
    else -> screen
}

/* ---- 主要应用入口 ---- */

/**
 * 应用根 Composable：导航宿主 + 主题 + 全部 Screen 路由。
 *
 * 职责：维护 back stack（remember 的 `List<Screen>`，[Screen.Main] 为栈底）、
 * 当前主 Tab 索引、各相册的显示偏好（排序/过滤/视图模式，提升到宿主以便返回恢复）、
 * 首次引导（OnboardingScreen）与手势引导浮层，并把 back stack 栈顶经
 * [resolveEditionScreen] 做 edition 降级后分发到对应 Screen。
 *
 * @param pluginViewModel lite 版传 null（showPluginManager=false）：插件管理/模型向导/
 *   JSON 编辑器 Screen 在 lite 下不可达，宿主不需要该 ViewModel
 * @param progressManager 可空；null（或用户关闭进度显示）时不渲染全局进度指示器浮层，
 *   但后台扫描/分析不受影响
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LocalAlbumApp(
    albumViewModel: AlbumViewModel,
    settingsViewModel: SettingsViewModel,
    pluginViewModel: PluginViewModel?,
    faceSwapViewModel: FaceSwapViewModel,
    editionFeatures: EditionFeatures,
    progressManager: ProgressManager? = null,
) {
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
    val themeMode = remember(settingsState.themeMode) {
        com.renyxin.localalbum.ui.theme.themeModeFromPersisted(settingsState.themeMode)
    }

    LaunchedEffect(settingsState.aiAnalysisPreferences) {
        com.renyxin.localalbum.core.analysis.AiAnalysisPreferencesRuntime.update(
            settingsState.aiAnalysisPreferences,
        )
    }

    var currentTab by remember { mutableIntStateOf(0) }
    // 相册详情页会在进入查看器时离开组合树；将显示偏好提升到导航宿主，返回时才能恢复。
    val albumSortModes = remember { mutableStateMapOf<String, SortMode>() }
    val albumFilters = remember { mutableStateMapOf<String, MediaFilter>() }
    val albumViewModes = remember { mutableStateMapOf<String, ViewMode>() }
    // 使用 back stack 管理导航历史，支持正确的返回行为
    var backStack by remember { mutableStateOf(listOf<Screen>(Screen.Main)) }
    val screen by remember(editionFeatures) {
        derivedStateOf {
            resolveEditionScreen(backStack.lastOrNull() ?: Screen.Main, editionFeatures)
        }
    }
    val navigationStateHolder = rememberSaveableStateHolder()
    val scanState by albumViewModel.scanState.collectAsStateWithLifecycle()
    val pipelineState by albumViewModel.libraryPipelineState.collectAsStateWithLifecycle()
    val coreScanActive = pipelineState.stage.isScan
    val progressScanMessage = (scanState as? ScanState.Scanning)
        ?.message
        ?.takeIf { pipelineState.stage.isScan }

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
                        com.renyxin.localalbum.ui.theme.themeModePersistedValue(selected)
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
        val resolved = resolveEditionScreen(target, editionFeatures)
        backStack = if (resolved == Screen.Main && target != Screen.Main) {
            listOf(Screen.Main)
        } else {
            backStack + resolved
        }
    }

    fun goBack() {
        if (backStack.size > 1) {
            backStack = backStack.dropLast(1)
        }
    }

    fun goBackTo(target: Screen) {
        val resolved = resolveEditionScreen(target, editionFeatures)
        // 回到指定的来源页面（弹出到该页面）
        val idx = backStack.indexOfLast { it.javaClass == resolved.javaClass }
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
        navigationStateHolder.SaveableStateProvider(navigationStateKey(s)) {
        when (s) {
        is Screen.AlbumDetail -> {
            val albumStateKey = s.album.directoryPath
            AlbumDetailScreen(
                album = s.album,
                mediaPaging = albumViewModel::pagedMediaForDirectory,
                onBack = { goBack() },
                sortMode = albumSortModes[albumStateKey] ?: SortMode.DATE_NEWEST,
                onSortModeChange = { albumSortModes[albumStateKey] = it },
                filter = albumFilters[albumStateKey] ?: MediaFilter.ALL,
                onFilterChange = { albumFilters[albumStateKey] = it },
                viewMode = albumViewModes[albumStateKey] ?: ViewMode.GRID,
                onViewModeChange = { albumViewModes[albumStateKey] = it },
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
                        kotlinx.coroutines.delay(GESTURE_GUIDE_AUTO_DISMISS_MS)
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
            val trashedCount by albumViewModel.trashedCount.collectAsStateWithLifecycle()
            val operationState by albumViewModel.trashOperationState.collectAsStateWithLifecycle()
            TrashScreen(
                trashedItems = trashedItems,
                totalCount = trashedCount,
                operationState = operationState,
                onOperationMessageConsumed = albumViewModel::consumeTrashOperationResult,
                onBack = { goBack() },
                onRestore = { paths -> albumViewModel.restoreFromTrash(paths) },
                onPermanentlyDelete = { paths -> albumViewModel.permanentlyDelete(paths) },
                onClearTrash = { albumViewModel.clearTrash() },
                loadAllTrashedPaths = { albumViewModel.getAllTrashedPaths() },
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

        is Screen.Edition -> {
            check(
                EditionUiContribution.renderDestination(
                    destinationId = s.destinationId,
                    albumViewModel = albumViewModel,
                    settingsViewModel = settingsViewModel,
                    scanState = scanState,
                    showAnalysisProgressUi = settingsState.showAnalysisProgressUi,
                    onBack = { goBack() },
                    onMediaClick = { item, queryContext ->
                        navigateTo(
                            Screen.MediaViewer(
                                initialPath = item.filePath,
                                queryContext = queryContext,
                                returnTo = s,
                            ),
                        )
                    },
                ),
            ) { "Unregistered edition destination reached: ${s.destinationId}" }
        }

        is Screen.Search -> {
            val optionalSearchMode = EditionSearchContribution.state(albumViewModel)
            var cameraModels by remember { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(Unit) { cameraModels = albumViewModel.getSearchModels() }
            SearchScreen(
                pagedSearch = albumViewModel::pagedSearch,
                cameraModels = cameraModels,
                optionalSearchMode = optionalSearchMode,
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
                viewModel = requireNotNull(pluginViewModel) { "Plugin manager is disabled for this edition" },
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

        is Screen.FaceSwap -> {
            FaceSwapScreen(
                viewModel = faceSwapViewModel,
                onBack = { goBack() },
            )
        }

        is Screen.ModelImportWizard -> {
            ModelImportWizardScreen(
                viewModel = requireNotNull(pluginViewModel) { "Plugin manager is disabled for this edition" },
                onNavigateBack = { goBack() },
            )
        }

        is Screen.ModelJsonEditor -> {
            ModelJsonEditorScreen(
                viewModel = requireNotNull(pluginViewModel) { "Plugin manager is disabled for this edition" },
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
                                if (currentTab != MainTabIndex.SETTINGS && coreScanActive) {
                                    IconButton(onClick = { currentTab = MainTabIndex.SETTINGS }) {
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
                            if (coreScanActive) {
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
                            editionFeatures = editionFeatures,
                            navigateTo = ::navigateTo,
                            onSwitchTab = { currentTab = it },
                        )
                        // Phase 5.2: 全局进度指示器浮层；设置仅隐藏 UI，不中止后台扫描/识别。
                        if (shouldShowGlobalProgressIndicator(
                            showAnalysisProgressUi = settingsState.showAnalysisProgressUi,
                            hasProgressManager = progressManager != null,
                        )) {
                            GlobalProgressIndicator(
                                progressManager = requireNotNull(progressManager),
                                pipelineState = pipelineState,
                                automaticAnalysisEnabled = editionFeatures.scanFeaturePolicy
                                    .stageIds(com.renyxin.localalbum.core.pipeline.AnalysisPlanType.ENHANCEMENT)
                                    .isNotEmpty(),
                                onCancel = albumViewModel::cancelTask,
                                modifier = Modifier.align(Alignment.BottomCenter),
                                scanMessage = progressScanMessage,
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
                        editionFeatures = editionFeatures,
                        navigateTo = ::navigateTo,
                        onSwitchTab = { currentTab = it },
                    )
                    // Phase 5.2: 全局进度指示器浮层；设置仅隐藏 UI，不中止后台扫描/识别。
                    if (shouldShowGlobalProgressIndicator(
                        showAnalysisProgressUi = settingsState.showAnalysisProgressUi,
                        hasProgressManager = progressManager != null,
                    )) {
                        GlobalProgressIndicator(
                            progressManager = requireNotNull(progressManager),
                            pipelineState = pipelineState,
                            automaticAnalysisEnabled = editionFeatures.scanFeaturePolicy
                                .stageIds(com.renyxin.localalbum.core.pipeline.AnalysisPlanType.ENHANCEMENT)
                                .isNotEmpty(),
                            onCancel = albumViewModel::cancelTask,
                            modifier = Modifier.align(Alignment.BottomCenter),
                            scanMessage = progressScanMessage,
                        )
                    }
                }
            }
            }
        }
        }
        }
    }
}

/**
 * 生成 [Screen] 在 SaveableStateHolder 中的状态 key。
 *
 * 稳定性要求：key = 类型前缀 + 身份字段（如 `album:{directoryPath}`、
 * `viewer:{initialPath}`），保证同一逻辑页面在重组/返回后命中同一保存状态，
 * 不同身份页面互不串状态；类型前缀避免不同 Screen 的身份字段碰撞。
 */
internal fun navigationStateKey(screen: Screen): String = when (screen) {
    Screen.Main -> "main"
    is Screen.AlbumDetail -> "album:${screen.album.directoryPath}"
    is Screen.MediaViewer -> "viewer:${screen.initialPath}"
    Screen.Search -> "search"
    Screen.Timeline -> "timeline"
    Screen.Trash -> "trash"
    Screen.DuplicatePhotos -> "duplicates"
    Screen.Favorites -> "favorites"
    Screen.Recommendations -> "recommendations"
    Screen.PluginManager -> "plugins"
    Screen.AnalysisPerformance -> "analysis-performance"
    Screen.FaceSwap -> "face-swap"
    Screen.ModelImportWizard -> "model-import"
    is Screen.ModelJsonEditor -> "model-editor:${screen.pluginId}"
    is Screen.Edition -> "edition:${screen.destinationId}"
    is Screen.RecommendationDetail ->
        "recommendation:${screen.recommendation.albumId}:${screen.recommendation.windowStart}"
}

/**
 * 全局进度指示器的显示条件：用户开启进度 UI 且宿主注入了 [ProgressManager]。
 * 设置项仅控制 UI 显示，不中止后台扫描/识别。
 */
internal fun shouldShowGlobalProgressIndicator(
    showAnalysisProgressUi: Boolean,
    hasProgressManager: Boolean,
): Boolean = showAnalysisProgressUi && hasProgressManager


@Composable
private fun currentTabContent(
    currentTab: Int,
    albumViewModel: AlbumViewModel,
    settingsViewModel: SettingsViewModel,
    editionFeatures: EditionFeatures,
    navigateTo: (Screen) -> Unit,
    onSwitchTab: (Int) -> Unit,
) {
    when (currentTab) {
        // Tab 0: 照片 — 时间线 + 顶部快捷入口卡片
        MainTabIndex.PHOTOS -> PhotosTab(
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
            onNavigateToSearch = { onSwitchTab(MainTabIndex.SEARCH) },
            onNavigateToFavorites = {
                navigateTo(Screen.Favorites)
            },
            onNavigateToDuplicate = {
                albumViewModel.loadDuplicateGroups()
                navigateTo(Screen.DuplicatePhotos)
            },
            onNavigateToEditionDestination = { destinationId ->
                navigateTo(Screen.Edition(destinationId))
            },
            onNavigateToRecommendations = {
                navigateTo(Screen.Recommendations)
            },
        )

        // Tab 1: 搜索
        MainTabIndex.SEARCH -> {
            val optionalSearchMode = EditionSearchContribution.state(albumViewModel)
            var cameraModels by remember { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(Unit) { cameraModels = albumViewModel.getSearchModels() }
            SearchScreen(
                pagedSearch = albumViewModel::pagedSearch,
                cameraModels = cameraModels,
                optionalSearchMode = optionalSearchMode,
                onBack = { onSwitchTab(MainTabIndex.PHOTOS) },
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
        MainTabIndex.ALBUMS -> AlbumsTab(
            viewModel = albumViewModel,
            onNavigateToSettings = { onSwitchTab(MainTabIndex.SETTINGS) },
            onAlbumClick = { album ->
                navigateTo(Screen.AlbumDetail(album, sourceTab = MainTabIndex.ALBUMS))
            },
        )

        // Tab 3: 设置（含回收站入口）
        MainTabIndex.SETTINGS -> SettingsTab(
            viewModel = settingsViewModel,
            albumViewModel = albumViewModel,
            editionFeatures = editionFeatures,
            onNavigateToTrash = { navigateTo(Screen.Trash) },
            onNavigateToPluginManager = { navigateTo(Screen.PluginManager) },
            onNavigateToFaceSwap = { navigateTo(Screen.FaceSwap) },
            onNavigateToAnalysisPerformance = { navigateTo(Screen.AnalysisPerformance) },
            onNavigateToEditionDestination = { destinationId ->
                navigateTo(Screen.Edition(destinationId))
            },
        )
    }
}

/* 照片 Tab 区块（PhotosTab/QuickAccessRow/QuickAccessCard/GestureGuideOverlay/RememberRestoreEffect）已提取至 ui/screens/PhotosTabScreen.kt。 */

/* 精选推荐区块（RecommendationTab/RecommendationDetailScreen/RecommendationCard/RecommendationThumbnailGrid）已提取至 ui/screens/RecommendationsSection.kt。 */
/* 相册 Tab 区块（AlbumsTab/AlbumTreeView/AlbumTreeRow/flattenAlbums/FlatAlbumRow/AlbumGridCard/AlbumCover）已提取至 ui/screens/AlbumsTabScreen.kt。 */


/* 设置 Tab（SettingsTab + MoreFeatureListItem + 7 个 Section）已提取并 Section 化至 ui/screens/settings/ 目录。 */



/* MediaEntity → MediaItem 转换扩展已提取至 UiCommon.kt。 */
