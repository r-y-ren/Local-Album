package com.renyxin.localalbum.ui.screens.settings

/**
 * 设置页 Section 2.7：相册排序模式（自 LocalAlbumApp.kt SettingsTab 纯 cut-paste 提取，行为不变）。
 */
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.renyxin.localalbum.ui.vm.AlbumViewModel
import com.renyxin.localalbum.ui.vm.SettingsViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlbumSortSection(
    albumSortMode: Int,
    viewModel: SettingsViewModel,
    albumViewModel: AlbumViewModel,
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
                        selected = albumSortMode == mode,
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
