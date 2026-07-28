package com.renyxin.localalbum.ui.screens

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.renyxin.localalbum.core.plugin.capability.ModelReadiness
import com.renyxin.localalbum.ui.vm.PluginViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ──────────────────────────────────────────────────────────────────────────────
// FaceSwapScreen — 换脸功能使用页面
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 换脸交互页面。
 *
 * 用户通过系统相册（SAF）选择源人脸图与目标图，点击「开始换脸」后调用内置
 * [com.renyxin.localalbum.core.plugin.extension.InSwapperPlugin] 执行换脸，
 * 结果预览后可保存到相册。
 *
 * 前置条件：换脸模型（inswapper_128.onnx）与人脸检测模型均就绪，否则禁用执行按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceSwapScreen(
    viewModel: PluginViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val coreStatuses by viewModel.coreFeatureStatuses.collectAsState()
    val faceSwapState by viewModel.faceSwapState.collectAsState()
    val faceSwapStatus = coreStatuses.firstOrNull { it.featureId == "face_swap" }
    val ready = faceSwapStatus?.readiness == ModelReadiness.READY

    var sourceFile by remember { mutableStateOf<File?>(null) }
    var targetFile by remember { mutableStateOf<File?>(null) }
    var sourcePreview by remember { mutableStateOf<Bitmap?>(null) }
    var targetPreview by remember { mutableStateOf<Bitmap?>(null) }

    // SAF 选图 launcher
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val file = copyUriToCache(context, uri, "faceswap_source")
            if (file != null) {
                sourceFile = file
                sourcePreview = decodeSampledBitmap(file, 512)
            }
        }
    }
    val targetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val file = copyUriToCache(context, uri, "faceswap_target")
            if (file != null) {
                targetFile = file
                targetPreview = decodeSampledBitmap(file, 512)
            }
        }
    }

    // 状态消息
    LaunchedEffect(faceSwapState) {
        when (val s = faceSwapState) {
            is PluginViewModel.FaceSwapUiState.Error ->
                snackbarHostState.showSnackbar(s.message)
            is PluginViewModel.FaceSwapUiState.Success ->
                snackbarHostState.showSnackbar("换脸完成，可保存到相册")
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("换脸") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 模型就绪状态提示 ──────────────────────────────
            ModelReadinessBanner(ready = ready)

            // ── 源人脸图选择 ──────────────────────────────────
            ImagePickerCard(
                title = "源人脸图",
                subtitle = "提供要换上去的人脸",
                bitmap = sourcePreview,
                onPick = { sourcePicker.launch(arrayOf("image/*")) },
                onClear = {
                    sourceFile = null
                    sourcePreview = null
                    viewModel.resetFaceSwapState()
                },
            )

            // ── 目标图选择 ────────────────────────────────────
            ImagePickerCard(
                title = "目标图",
                subtitle = "被替换人脸的底图",
                bitmap = targetPreview,
                onPick = { targetPicker.launch(arrayOf("image/*")) },
                onClear = {
                    targetFile = null
                    targetPreview = null
                    viewModel.resetFaceSwapState()
                },
            )

            // ── 执行换脸 ──────────────────────────────────────
            val canSwap = ready && sourceFile != null && targetFile != null &&
                faceSwapState !is PluginViewModel.FaceSwapUiState.Loading

            Button(
                onClick = {
                    val s = sourceFile ?: return@Button
                    val t = targetFile ?: return@Button
                    viewModel.performFaceSwap(s, t)
                },
                enabled = canSwap,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (faceSwapState is PluginViewModel.FaceSwapUiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("换脸中…")
                } else {
                    Text("开始换脸")
                }
            }

            // ── 结果预览 ──────────────────────────────────────
            when (val s = faceSwapState) {
                is PluginViewModel.FaceSwapUiState.Success -> {
                    ResultPreviewCard(
                        bitmap = s.bitmap,
                        onSave = {
                            scope.launch {
                                val ok = saveBitmapToGallery(context, s.bitmap)
                                snackbarHostState.showSnackbar(
                                    if (ok) "已保存到相册 Pictures/LocalAlbum" else "保存失败"
                                )
                            }
                        },
                        onRedo = { viewModel.resetFaceSwapState() },
                    )
                }
                else -> Unit
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 子组件
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModelReadinessBanner(
    ready: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ready)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (ready) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (ready)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    if (ready) "换脸模型已就绪" else "换脸模型未就绪",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (ready)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onErrorContainer,
                )
                if (!ready) {
                    Text(
                        "请确认人脸聚类与换脸模型均已加载完成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImagePickerCard(
    title: String,
    subtitle: String,
    bitmap: Bitmap?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.weight(1f))
                if (bitmap != null) {
                    TextButton(onClick = onClear) { Text("清除") }
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onPick() },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.AddPhotoAlternate,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "点击选择图片",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultPreviewCard(
    bitmap: Bitmap,
    onSave: () -> Unit,
    onRedo: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("换脸结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "换脸结果",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onRedo,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("重新选择") }
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Filled.Save, null, Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("保存到相册")
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 工具函数
// ──────────────────────────────────────────────────────────────────────────────

/** 将 SAF Uri 内容复制到应用缓存目录，返回临时 File（供插件按文件路径读取）。 */
private fun copyUriToCache(context: Context, uri: Uri, prefix: String): File? {
    return try {
        val tempFile = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        tempFile
    } catch (e: Exception) {
        Log.w("FaceSwapScreen", "复制 Uri 到缓存失败", e)
        null
    }
}

/** 解码下采样 Bitmap 用于预览，避免大图 OOM。 */
private fun decodeSampledBitmap(file: File, reqSize: Int): Bitmap? {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > reqSize || opts.outHeight / sample > reqSize) sample *= 2
        val full = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, full)
    } catch (e: Exception) {
        Log.w("FaceSwapScreen", "解码预览失败", e)
        null
    }
}

/** 将换脸结果 Bitmap 保存到相册 Pictures/LocalAlbum，返回是否成功。 */
private suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val name = "faceswap_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LocalAlbum")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = resolver.insert(collection, values) ?: return@withContext false
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            } ?: return@withContext false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            Log.e("FaceSwapScreen", "保存到相册失败", e)
            false
        }
    }
