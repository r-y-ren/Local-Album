package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Fts4
import com.renyxin.localalbum.core.search.FtsTextCodec

/**
 * FTS4 全文索引虚拟表 (独立表, 带 Unicode 分词器)。
 * 对 fileName、parentPath、ocrText、make、model 建立全文索引以支持高速搜索。
 * 使用 unicode61 分词器，支持中文等 Unicode 字符的 tokenization。
 * kapt 不支持 @Fts4(contentEntity=...) 跨类引用，
 * 因此使用独立 FTS4 表，由 DAO 手动维护索引数据。
 */
@Fts4(tokenizer = "unicode61")
@Entity(tableName = "media_items_fts")
data class MediaFts(
    val filePath: String,
    val fileName: String,
    val parentPath: String,
    val ocrText: String?,
    val make: String?,
    val model: String?,
)

/**
 * 统一的 FTS 行构造入口：所有文本列先经 [FtsTextCodec.indexable] 展开
 * （汉字按字 + 二元词组），使中文子串可通过短语查询命中；拉丁片段原样保留。
 * 原始文本以 media_items 主表为准，FTS 内容是可再生的派生数据。
 */
fun indexedMediaFts(
    filePath: String,
    fileName: String?,
    parentPath: String?,
    ocrText: String?,
    make: String?,
    model: String?,
): MediaFts = MediaFts(
    filePath = filePath,
    fileName = FtsTextCodec.indexable(fileName),
    parentPath = FtsTextCodec.indexable(parentPath),
    ocrText = FtsTextCodec.indexable(ocrText),
    make = make,
    model = model,
)

/** 主表实体 → FTS 行的便捷入口（扫描提交路径统一使用）。 */
fun indexedMediaFtsOf(entity: MediaEntity): MediaFts = indexedMediaFts(
    filePath = entity.filePath,
    fileName = entity.fileName,
    parentPath = entity.parentPath,
    ocrText = entity.ocrText,
    make = entity.make,
    model = entity.model,
)
