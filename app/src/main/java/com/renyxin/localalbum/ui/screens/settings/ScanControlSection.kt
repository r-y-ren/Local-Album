package com.renyxin.localalbum.ui.screens.settings

/**
 * 设置页 Section 3：扫描操作卡片（自 LocalAlbumApp.kt SettingsTab 纯 cut-paste 提取，行为不变）。
 *
 * 内容：开始扫描按钮与核心扫描/后台增强状态文案。
 */
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.renyxin.localalbum.data.db.entity.CoreScanState
import com.renyxin.localalbum.data.db.entity.EnhancementState
import com.renyxin.localalbum.data.db.entity.IndexAvailability
import com.renyxin.localalbum.data.repo.ScanState
import com.renyxin.localalbum.ui.coreScanStatusText
import com.renyxin.localalbum.ui.enhancementStatusText
import com.renyxin.localalbum.ui.vm.AlbumViewModel

@Composable
internal fun ScanControlSection(
    coreScanActive: Boolean,
    hasScanRoots: Boolean,
    scanState: ScanState,
    indexAvailability: IndexAvailability,
    coreScanState: CoreScanState,
    enhancementState: EnhancementState,
    albumViewModel: AlbumViewModel,
) {
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
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )

            Button(
                onClick = { albumViewModel.rescan() },
                enabled = !coreScanActive && hasScanRoots,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (coreScanActive) "扫描中…"
                    else "开始扫描媒体文件"
                )
            }

            Text(
                text = coreScanStatusText(indexAvailability, coreScanState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = enhancementStatusText(enhancementState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )

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

                ScanState.Done, ScanState.Idle -> Unit
            }
        }
    }
}
