package com.renyxin.localalbum.ui.screens.settings

/**
 * 设置页 Section 0：更多功能入口（自 LocalAlbumApp.kt SettingsTab 纯 cut-paste 提取，行为不变）。
 *
 * 内容：插件管理/换脸（按 edition）、扫描与分析性能、edition 扩展入口与回收站。
 */
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.renyxin.localalbum.edition.EditionFeatures
import com.renyxin.localalbum.edition.EditionUiContribution

@Composable
internal fun MoreFeaturesSection(
    editionFeatures: EditionFeatures,
    trashedCount: Int,
    onNavigateToTrash: () -> Unit,
    onNavigateToPluginManager: () -> Unit,
    onNavigateToFaceSwap: () -> Unit,
    onNavigateToAnalysisPerformance: () -> Unit,
    onNavigateToEditionDestination: (String) -> Unit,
) {
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
            if (editionFeatures.showPluginManager) {
                MoreFeatureListItem(
                    title = "AI 插件管理",
                    description = "导入和管理 AI 模型插件",
                    imageVector = Icons.Filled.PlayArrow,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = onNavigateToPluginManager,
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            } else if (editionFeatures.showFaceSwap) {
                MoreFeatureListItem(
                    title = "换脸",
                    description = "选择源人脸与目标照片进行换脸",
                    imageVector = Icons.Filled.Face,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = onNavigateToFaceSwap,
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            MoreFeatureListItem(
                title = "扫描与分析性能",
                description = "按设备能力选择稳定、均衡或性能调度",
                imageVector = Icons.Default.Settings,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = onNavigateToAnalysisPerformance,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            EditionUiContribution.settingsFeatureEntries(
                onNavigate = onNavigateToEditionDestination,
            )
            MoreFeatureListItem(
                title = "回收站",
                description = if (trashedCount == 0) "空" else "$trashedCount 项待清理",
                imageVector = Icons.Default.DeleteOutline,
                iconTint = MaterialTheme.colorScheme.error,
                onClick = onNavigateToTrash,
            )
        }
    }
}

/**
 * 设置页"更多功能"列表项：图标 + 标题/描述 + 右箭头（自 LocalAlbumApp.kt 纯 cut-paste 提取）。
 */
@Composable
internal fun MoreFeatureListItem(
    title: String,
    description: String,
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    // Material ListItem 会根据文字行数改变 leadingContent 的垂直布局规则。
    // 使用自定义 Row 后，图标和箭头始终相对于整项（包括换行描述）垂直居中。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = iconTint,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
