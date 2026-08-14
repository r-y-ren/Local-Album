package com.renyxin.localalbum.ui.screens.settings

/**
 * 设置页 Section 2：忽略规则（正则）（自 LocalAlbumApp.kt SettingsTab 纯 cut-paste 提取，行为不变）。
 *
 * 内容：正则输入行与已配置规则的 FlowRow 删除入口。
 */
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.renyxin.localalbum.ui.vm.SettingsViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IgnoreRulesSection(
    ignoreDirNames: List<String>,
    viewModel: SettingsViewModel,
) {
    var newIgnore by remember { mutableStateOf("") }

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

            if (ignoreDirNames.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ignoreDirNames.forEach { name ->
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
