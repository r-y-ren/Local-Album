package com.renyxin.localalbum.ui.screens.settings

/**
 * 设置页 Section 2.5 / 2.6：显示开关（自 LocalAlbumApp.kt SettingsTab 纯 cut-paste 提取，行为不变）。
 *
 * 内容：.nomedia 目录显示开关与扫描识别进度 UI 开关。
 */
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.renyxin.localalbum.ui.vm.SettingsViewModel

@Composable
internal fun NomediaToggleSection(
    showNomediaDirectories: Boolean,
    viewModel: SettingsViewModel,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
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
                    checked = showNomediaDirectories,
                    onCheckedChange = { viewModel.setShowNomediaDirectories(it) },
                )
            }
        }
    }
}

@Composable
internal fun ProgressUiToggleSection(
    showAnalysisProgressUi: Boolean,
    viewModel: SettingsViewModel,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "显示扫描识别进度",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "控制全局进度浮层及人物页逐文件进度。关闭后扫描与 AI 识别仍会在后台继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            androidx.compose.material3.Switch(
                checked = showAnalysisProgressUi,
                onCheckedChange = viewModel::setShowAnalysisProgressUi,
            )
        }
    }
}
