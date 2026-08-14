package com.renyxin.localalbum.ui.screens.settings

/**
 * 设置页 Section 4：备份与恢复（自 LocalAlbumApp.kt SettingsTab 纯 cut-paste 提取，行为不变）。
 *
 * 内容：SAF 导出/导入按钮、备份状态展示与导入确认对话框。
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.renyxin.localalbum.ui.BACKUP_TIMESTAMP_PATTERN
import com.renyxin.localalbum.ui.vm.AlbumViewModel

@Composable
internal fun BackupRestoreSection(albumViewModel: AlbumViewModel) {
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
                    val timestamp = java.text.SimpleDateFormat(
                        BACKUP_TIMESTAMP_PATTERN,
                        java.util.Locale.getDefault(),
                    ).format(java.util.Date())
                    val fileName = "localalbum_backup_$timestamp.zip"
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
