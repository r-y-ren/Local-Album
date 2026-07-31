package com.renyxin.localalbum.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.renyxin.localalbum.data.repo.AlbumSyncState
import kotlinx.coroutines.delay

@Composable
fun AlbumSyncStatusBanner(
    state: AlbumSyncState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var successVisible by remember(state) { mutableStateOf(state is AlbumSyncState.UpToDate) }
    LaunchedEffect(state) {
        if (state is AlbumSyncState.UpToDate) {
            successVisible = true
            delay(3_000L)
            successVisible = false
        }
    }
    val visible = when (state) {
        is AlbumSyncState.UpToDate -> successVisible
        AlbumSyncState.FirstBuild -> false
        else -> true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val failed = state is AlbumSyncState.Failed || state is AlbumSyncState.Paused
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(14.dp),
            color = if (failed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                when (state) {
                    is AlbumSyncState.UpToDate -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    is AlbumSyncState.Failed, is AlbumSyncState.Paused -> Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    else -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = albumSyncStatusText(state),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (failed || state is AlbumSyncState.Paused) {
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重试")
                    }
                }
            }
        }
    }
}

internal fun albumSyncStatusText(state: AlbumSyncState): String = when (state) {
    AlbumSyncState.Restoring -> "正在载入上次相册…"
    AlbumSyncState.FirstBuild -> "正在首次建立相册…"
    is AlbumSyncState.Checking -> "正在检查新照片…"
    is AlbumSyncState.Updating -> if (state.total > 0) {
        "正在更新相册 · ${state.processed}/${state.total}"
    } else {
        "正在更新相册…"
    }
    is AlbumSyncState.UpToDate -> "相册已更新"
    is AlbumSyncState.Paused -> if (state.hasCachedAlbums) {
        "更新已暂停，当前显示上次结果"
    } else {
        "首次更新已暂停"
    }
    is AlbumSyncState.Failed -> if (state.hasCachedAlbums) {
        "更新失败，当前显示上次结果"
    } else {
        "相册更新失败：${state.message}"
    }
}
