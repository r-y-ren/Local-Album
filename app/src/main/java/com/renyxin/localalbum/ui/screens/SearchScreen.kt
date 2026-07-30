package com.renyxin.localalbum.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.data.repo.MediaSearchQuery
import com.renyxin.localalbum.data.repo.SemanticSearchResult
import com.renyxin.localalbum.data.repo.SemanticSearchState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class SearchFilterType(val label: String) {
    ALL("全部"),
    IMAGES("图片"),
    VIDEOS("视频"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    pagedSearch: (MediaSearchQuery) -> Flow<PagingData<MediaItem>>,
    cameraModels: List<String>,
    semanticSearchResults: List<SemanticSearchResult> = emptyList(),
    isSemanticMode: Boolean = false,
    semanticSearchState: SemanticSearchState = SemanticSearchState.IDLE,
    onSemanticModeChange: (Boolean) -> Unit = {},
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onBack: () -> Unit,
    onMediaClick: (MediaItem, MediaSearchQuery?, List<String>?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    var typeFilter by remember { mutableStateOf(SearchFilterType.ALL) }
    var showDateFromPicker by remember { mutableStateOf(false) }
    var showDateToPicker by remember { mutableStateOf(false) }
    var dateFrom by remember { mutableStateOf<LocalDate?>(null) }
    var dateTo by remember { mutableStateOf<LocalDate?>(null) }

    var cameraFilter by remember { mutableStateOf<String?>(null) }

    // Debounce search
    LaunchedEffect(query, isSemanticMode) {
        if (query.isNotBlank()) {
            delay(300)
            onSearch(query)
        } else {
            onClearSearch()
        }
    }

    // 语义索引会在扫描期间按批次逐步写入数据库。首次查询可能早于首批落库而得到
    // NO_INDEX；在该状态下定时重试当前查询，首批可搜索向量出现后立即展示结果。
    LaunchedEffect(query, isSemanticMode, semanticSearchState) {
        if (isSemanticMode && query.isNotBlank() && semanticSearchState == SemanticSearchState.NO_INDEX) {
            delay(1_000L)
            onSearch(query)
        }
    }

    val searchQuery = remember(query, typeFilter, dateFrom, dateTo, cameraFilter) {
        MediaSearchQuery(
            text = query,
            mediaType = when (typeFilter) {
                SearchFilterType.ALL -> null
                SearchFilterType.IMAGES -> MediaType.IMAGE
                SearchFilterType.VIDEOS -> MediaType.VIDEO
            },
            startMs = dateFrom?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
            endMs = dateTo?.plusDays(1)?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()?.minus(1),
            model = cameraFilter,
        )
    }
    val keywordItems = remember(searchQuery) { pagedSearch(searchQuery) }.collectAsLazyPagingItems()

    // 语义搜索保持有界列表；其筛选仍可在内存执行。
    val filteredResults = remember(semanticSearchResults, typeFilter, dateFrom, dateTo, cameraFilter) {
        semanticSearchResults.filter { result ->
            val item = result.mediaItem
            val typeMatch = when (typeFilter) {
                SearchFilterType.ALL -> true
                SearchFilterType.IMAGES -> item.type == MediaType.IMAGE
                SearchFilterType.VIDEOS -> item.type == MediaType.VIDEO
            }
            val dateMatch = if (dateFrom != null || dateTo != null) {
                val itemDate = LocalDate.ofInstant(item.capturedAt, ZoneId.systemDefault())
                (dateFrom == null || !itemDate.isBefore(dateFrom)) &&
                    (dateTo == null || !itemDate.isAfter(dateTo))
            } else true
            val cameraMatch = cameraFilter == null || item.model == cameraFilter
            typeMatch && dateMatch && cameraMatch
        }
    }

    val activeFilterCount = remember(typeFilter, dateFrom, dateTo, cameraFilter) {
        var count = 0
        if (typeFilter != SearchFilterType.ALL) count++
        if (dateFrom != null) count++
        if (dateTo != null) count++
        if (cameraFilter != null) count++
        count
    }

    // Date picker dialogs
    if (showDateFromPicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = dateFrom?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDateFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        dateFrom = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    showDateFromPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDateFromPicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showDateToPicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = dateTo?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDateToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        dateTo = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    showDateToPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDateToPicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Scaffold(
        topBar = {
            Surface(
                shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(if (isSemanticMode) "语义搜索（请用英文）：sunset, beach…" else "搜索图片或视频…")
                            },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = {
                                        query = ""
                                        onClearSearch()
                                    }) {
                                        Icon(Icons.Default.Clear, contentDescription = "清除")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { showFilters = !showFilters }) {
                            Icon(
                                Icons.Default.FilterList,
                                contentDescription = "过滤",
                                tint = if (activeFilterCount > 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val visibleCount = if (isSemanticMode) filteredResults.size else keywordItems.itemCount
                        if (query.isNotEmpty() && visibleCount > 0) {
                            Text(
                                text = "$visibleCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }

                    // Semantic search mode toggle row
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 1.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = if (isSemanticMode) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "语义搜索",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSemanticMode) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSemanticMode) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isSemanticMode) "（英文自然语言匹配图片内容）" else "（关键词匹配）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.weight(1f))
                            Switch(
                                checked = isSemanticMode,
                                onCheckedChange = onSemanticModeChange,
                            )
                        }
                    }

                    // Advanced filter panel
                    AnimatedVisibility(
                        visible = showFilters,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 1.dp,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // Type filter
                                Text("媒体类型", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SearchFilterType.entries.forEach { ft ->
                                        FilterChip(
                                            selected = typeFilter == ft,
                                            onClick = { typeFilter = ft },
                                            label = { Text(ft.label) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = when (ft) {
                                                        SearchFilterType.ALL -> Icons.Default.Search
                                                        SearchFilterType.IMAGES -> Icons.Default.Image
                                                        SearchFilterType.VIDEOS -> Icons.Default.Videocam
                                                    },
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                        )
                                    }
                                }

                                // Date range filter
                                Text("拍摄日期范围", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    FilterChip(
                                        selected = dateFrom != null,
                                        onClick = { showDateFromPicker = true },
                                        label = {
                                            Text(
                                                if (dateFrom != null) "从: ${formatLocalDate(dateFrom!!)}"
                                                else "开始日期"
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                                        },
                                        trailingIcon = if (dateFrom != null) {
                                            {
                                                IconButton(
                                                    onClick = { dateFrom = null },
                                                    modifier = Modifier.size(16.dp),
                                                ) { Icon(Icons.Default.Clear, "清除", modifier = Modifier.size(14.dp)) }
                                            }
                                        } else null,
                                    )
                                    FilterChip(
                                        selected = dateTo != null,
                                        onClick = { showDateToPicker = true },
                                        label = {
                                            Text(
                                                if (dateTo != null) "至: ${formatLocalDate(dateTo!!)}"
                                                else "结束日期"
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                                        },
                                        trailingIcon = if (dateTo != null) {
                                            {
                                                IconButton(
                                                    onClick = { dateTo = null },
                                                    modifier = Modifier.size(16.dp),
                                                ) { Icon(Icons.Default.Clear, "清除", modifier = Modifier.size(14.dp)) }
                                            }
                                        } else null,
                                    )
                                }

                                // Camera model filter
                                if (cameraModels.isNotEmpty()) {
                                    Text("拍摄设备", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = cameraFilter == null,
                                            onClick = { cameraFilter = null },
                                            label = { Text("全部") },
                                        )
                                        cameraModels.forEach { model ->
                                            FilterChip(
                                                selected = cameraFilter == model,
                                                onClick = { cameraFilter = if (cameraFilter == model) null else model },
                                                label = { Text(model, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                leadingIcon = {
                                                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                                },
                                            )
                                        }
                                    }
                                }

                                // Reset all filters
                                if (activeFilterCount > 0) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        TextButton(onClick = {
                                            typeFilter = SearchFilterType.ALL
                                            dateFrom = null
                                            dateTo = null
                                            cameraFilter = null
                                        }) {
                                            Text("重置全部过滤条件")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (query.isBlank()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(48.dp))
                Icon(
                    imageVector = if (isSemanticMode) Icons.Default.AutoAwesome else Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (isSemanticMode) "输入英文描述搜索图片内容" else "输入关键词搜索媒体文件",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isSemanticMode) "如 sunset, beach, food, night view 等（语义模型对中文支持较差，请使用英文）"
                        else "支持搜索文件名、拍摄设备、日期等",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                // 快捷搜索词卡片，对齐谷歌相册搜索页的灵感词区
                if (isSemanticMode) {
                    Spacer(Modifier.height(32.dp))
                    Text(
                        text = "试试搜索",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("sunset", "beach", "food", "night view", "pet", "selfie", "landscape", "document").forEach { word ->
                            AssistChip(
                                onClick = {
                                    query = word
                                    onSearch(word)
                                },
                                label = { Text(word) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }
            return@Scaffold
        }

        val noResults = if (isSemanticMode) filteredResults.isEmpty()
            else keywordItems.itemCount == 0 && keywordItems.loadState.refresh !is androidx.paging.LoadState.Loading
        if (noResults) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 语义搜索中：显示加载指示器
                    if (isSemanticMode && semanticSearchState == SemanticSearchState.SEARCHING) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("正在语义搜索…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                        return@Column
                    }

                    // 根据语义搜索状态显示不同的提示信息
                    val (titleText, subText) = if (isSemanticMode) {
                        when (semanticSearchState) {
                            SemanticSearchState.MODEL_NOT_READY ->
                                "语义搜索模型加载失败" to "CLIP 模型加载未成功，请稍后重试。如持续失败请检查设备可用内存（需约 500MB）"
                            SemanticSearchState.NO_INDEX ->
                                "尚未建立语义索引" to "请先在设置中扫描并分析图片，完成后再使用语义搜索"
                            SemanticSearchState.NO_RESULTS ->
                                "未找到与 \"$query\" 语义匹配的媒体" to "尝试更换英文关键词，如 sunset, beach, food, night view"
                            else ->
                                "未找到与 \"$query\" 语义匹配的媒体" to "语义搜索需要先完成媒体分析，请确保已扫描并分析图片"
                        }
                    } else {
                        "未找到匹配 \"$query\" 的媒体" to
                            if (activeFilterCount > 0) "当前有 $activeFilterCount 个活跃过滤条件，请尝试调整或重置" else ""
                    }

                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    if (subText.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = subText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (isSemanticMode) {
                items(filteredResults, key = { it.mediaItem.id }) { result ->
                    SearchMediaThumbnail(
                        item = result.mediaItem,
                        similarity = result.similarity,
                        showScore = true,
                        onClick = {
                            onMediaClick(result.mediaItem, null, filteredResults.map { it.mediaItem.filePath })
                        },
                    )
                }
            } else {
                items(
                    count = keywordItems.itemCount,
                    key = keywordItems.itemKey { it.filePath },
                ) { index ->
                    keywordItems[index]?.let { item ->
                        SearchMediaThumbnail(
                            item = item,
                            onClick = { onMediaClick(item, searchQuery, null) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchMediaThumbnail(
    item: MediaItem,
    similarity: Float = 0f,
    showScore: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(120.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomEnd,
    ) {
        val context = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.thumbnailPath ?: item.filePath)
                .crossfade(true)
                .size(300, 300)
                .build(),
            contentDescription = item.fileName,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        if (item.type == MediaType.VIDEO) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(24.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = "视频",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // Semantic similarity score badge (Phase 4.1)
        if (showScore && similarity > 0f) {
            val scorePercent = (similarity * 100).toInt()
            val scoreColor = when {
                similarity >= 0.7f -> Color(0xFF4CAF50) // 绿色 - 高匹配
                similarity >= 0.4f -> Color(0xFFFF9800) // 橙色 - 中匹配
                else -> Color(0xFF9E9E9E) // 灰色 - 低匹配
            }
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .background(scoreColor.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "$scorePercent%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun formatLocalDate(date: LocalDate): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
    return date.format(formatter)
}
