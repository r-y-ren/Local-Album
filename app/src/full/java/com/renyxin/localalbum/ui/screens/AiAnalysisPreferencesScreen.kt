package com.renyxin.localalbum.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.renyxin.localalbum.core.analysis.AiAnalysisPreferences
import com.renyxin.localalbum.core.analysis.FaceGroupingStrictness
import com.renyxin.localalbum.core.analysis.OcrAnalysisScope
import com.renyxin.localalbum.core.analysis.RecommendationPreference
import com.renyxin.localalbum.core.analysis.SemanticSearchStrictness

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AiAnalysisPreferencesScreen(
    savedPreferences: AiAnalysisPreferences,
    onSave: (AiAnalysisPreferences) -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember(savedPreferences) { mutableStateOf(savedPreferences) }
    var applied by remember(savedPreferences) { mutableStateOf(savedPreferences) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 识别与结果偏好") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                PreferenceCard("人物识别", "修改后影响后续人物归类；已有结果需执行人物重新整理才能完全统一。") {
                    ChoiceRow("人物归并", FaceGroupingStrictness.entries, draft.faceGroupingStrictness, {
                        when (it) {
                            FaceGroupingStrictness.STRICT -> "严格区分"
                            FaceGroupingStrictness.STANDARD -> "标准"
                            FaceGroupingStrictness.RELAXED -> "宽松归并"
                        }
                    }) { draft = draft.copy(faceGroupingStrictness = it) }
                    ChoiceRow("最少成组人脸", listOf(1, 2, 3, 5), draft.faceMinimumGroupSize, { "$it 张" }) {
                        draft = draft.copy(faceMinimumGroupSize = it)
                    }
                }
            }
            item {
                PreferenceCard("OCR 文字识别", "仅截图和文档模式依赖场景标签；关闭可明显缩短新媒体分析时间。") {
                    ChoiceRow("分析范围", OcrAnalysisScope.entries, draft.ocrAnalysisScope, {
                        when (it) {
                            OcrAnalysisScope.ALL_MEDIA -> "所有媒体"
                            OcrAnalysisScope.DOCUMENTS_AND_SCREENSHOTS -> "仅截图和文档"
                            OcrAnalysisScope.DISABLED -> "关闭"
                        }
                    }) { draft = draft.copy(ocrAnalysisScope = it) }
                }
            }
            item {
                PreferenceCard("语义搜索", "仅影响新查询，无需重建语义索引。") {
                    ChoiceRow("匹配严格程度", SemanticSearchStrictness.entries, draft.semanticSearchStrictness, {
                        when (it) {
                            SemanticSearchStrictness.MORE_RESULTS -> "更多结果"
                            SemanticSearchStrictness.STANDARD -> "标准"
                            SemanticSearchStrictness.PRECISE -> "精确匹配"
                        }
                    }) { draft = draft.copy(semanticSearchStrictness = it) }
                    ChoiceRow("默认结果数量", listOf(20, 50, 100), draft.semanticSearchResultCount, { "$it 条" }) {
                        draft = draft.copy(semanticSearchResultCount = it)
                    }
                }
            }
            item {
                PreferenceCard("精选推荐", "保存后刷新精选推荐即可生效，不会重新运行 AI 模型。") {
                    ChoiceRow("推荐倾向", RecommendationPreference.entries, draft.recommendationPreference, {
                        when (it) {
                            RecommendationPreference.BALANCED -> "均衡"
                            RecommendationPreference.QUALITY -> "画质"
                            RecommendationPreference.FAVORITES -> "收藏"
                            RecommendationPreference.MEMORIES -> "旧时回忆"
                            RecommendationPreference.RECENT -> "最近照片"
                        }
                    }) { draft = draft.copy(recommendationPreference = it) }
                }
            }
            item {
                OutlinedButton(
                    onClick = { draft = AiAnalysisPreferences() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("恢复默认值") }
                Button(
                    onClick = {
                        val value = draft.normalized()
                        onSave(value)
                        applied = value
                    },
                    enabled = draft.normalized() != applied,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(if (draft.normalized() == applied) "当前设置已保存" else "保存并应用") }
                Text(
                    "人物参数不会自动破坏已有分组；搜索和推荐偏好立即用于下一次查询或刷新，OCR 范围用于下一个分析窗口。",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreferenceCard(title: String, description: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = selected == option, onClick = { onSelected(option) }, label = { Text(label(option)) })
        }
    }
}
