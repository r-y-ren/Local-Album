package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * FTS4 全文索引虚拟表 (独立表, 带 Unicode 分词器)。
 * 对 fileName、ocrText、make、model 建立全文索引以支持高速搜索。
 * 使用 unicode61 分词器，支持中文等 Unicode 字符的 tokenization。
 * kapt 不支持 @Fts4(contentEntity=...) 跨类引用，
 * 因此使用独立 FTS4 表，由 DAO 手动维护索引数据。
 */
@Fts4(tokenizer = "unicode61")
@Entity(tableName = "media_items_fts")
data class MediaFts(
    val filePath: String,
    val fileName: String,
    val ocrText: String?,
    val make: String?,
    val model: String?,
)
