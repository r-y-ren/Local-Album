package com.renyxin.localalbum.core.plugin.capability.builtin

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * CLIP BPE 分词器（标准 open_clip 实现）。
 *
 * 从 assets 读取 `vocab.json`（token→id）与 `merges.txt`（BPE 合并优先级），
 * 将自然语言文本编码为 CLIP 文本编码器所需的 input_ids（长度固定为 [contextLength]）。
 *
 * 算法与 HuggingFace `CLIPTokenizer` / open_clip 一致：
 * 1. 文本小写化 + CLIP 正则切词
 * 2. 每个词做 byte-level BPE（字节→Unicode 映射，避免不可见控制字符）
 * 3. 按 merges 优先级合并，得到子词序列
 * 4. 查 vocab.json 得 token id
 * 5. 前后加 `<|startoftext|>` / `<|endoftext|>`，截断/补零到 [contextLength]
 *
 * @param context Android 上下文，用于访问 assets
 * @param assetDir CLIP 模型所在 assets 子目录（如 "models/eva02_clip"）
 * @param contextLength 文本最大 token 长度（CLIP 默认 77）
 */
class ClipBpeTokenizer(
    context: Context,
    private val assetDir: String,
    private val contextLength: Int = 77,
) {
    companion object {
        private const val SOT_TOKEN = "<|startoftext|>"
        private const val EOT_TOKEN = "<|endoftext|>"
        // CLIP 正则：与 open_clip/transformers 一致
        private val CLIP_PATTERN = Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]+|[^\s\p{L}\p{N}]+""",
            RegexOption.IGNORE_CASE,
        )
    }

    private val vocab: Map<String, Int>
    private val merges: Map<String, Int>
    private val byteEncoder: Map<Int, String>
    private val sotId: Int
    private val eotId: Int

    init {
        val asset = context.assets
        vocab = loadVocab(BufferedReader(InputStreamReader(asset.open("$assetDir/vocab.json"))))
        merges = loadMerges(BufferedReader(InputStreamReader(asset.open("$assetDir/merges.txt"))))
        byteEncoder = bytesToUnicode()
        sotId = vocab[SOT_TOKEN] ?: 49406
        eotId = vocab[EOT_TOKEN] ?: 49407
    }

    /** 词表大小 */
    val vocabSize: Int get() = vocab.size

    /**
     * 将文本编码为固定长度的 token id 数组（Long），不足补 0，超长截断保留 SOT…EOT。
     */
    fun encode(text: String): LongArray {
        val ids = ArrayList<Int>(contextLength)
        ids.add(sotId)
        if (text.isNotBlank()) {
            for (tok in bpeEncode(text)) {
                val id = vocab[tok]
                if (id != null) ids.add(id)
                if (ids.size >= contextLength - 1) break
            }
        }
        ids.add(eotId)
        // 截断或补零
        val out = LongArray(contextLength)
        val n = minOf(ids.size, contextLength)
        for (i in 0 until n) out[i] = ids[i].toLong()
        // 末位若被截断，强制为 EOT
        if (ids.size >= contextLength) out[contextLength - 1] = eotId.toLong()
        return out
    }

    // ---- BPE 核心 ----

    private fun bpeEncode(text: String): List<String> {
        val tokens = ArrayList<String>()
        for (raw in CLIP_PATTERN.findAll(text.lowercase())) {
            val word = raw.value
            // byte-level 映射
            val mapped = StringBuilder(word.length)
            for (b in word.toByteArray(Charsets.UTF_8)) {
                mapped.append(byteEncoder[b.toInt() and 0xFF])
            }
            val mappedStr = mapped.toString()
            if (mappedStr.isEmpty()) continue

            // 切成单字符符号
            val symbols = mappedStr.map { it.toString() }.toMutableList()
            bpeMerge(symbols)
            tokens.addAll(symbols)
        }
        return tokens
    }

    private fun bpeMerge(symbols: MutableList<String>) {
        if (symbols.size < 2) return
        while (true) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1
            for (i in 0 until symbols.size - 1) {
                val pair = symbols[i] + " " + symbols[i + 1]
                val rank = merges[pair]
                if (rank != null && rank < bestRank) {
                    bestRank = rank
                    bestIdx = i
                }
            }
            if (bestIdx < 0) break
            // 合并 bestIdx 与 bestIdx+1
            symbols[bestIdx] = symbols[bestIdx] + symbols[bestIdx + 1]
            symbols.removeAt(bestIdx + 1)
            if (symbols.size < 2) break
        }
    }

    // ---- 资源加载 ----

    private fun loadVocab(reader: BufferedReader): Map<String, Int> {
        val text = reader.use { it.readText() }
        val obj = JSONObject(text)
        val map = HashMap<String, Int>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.getInt(k)
        }
        return map
    }

    private fun loadMerges(reader: BufferedReader): Map<String, Int> {
        val map = HashMap<String, Int>()
        reader.useLines { lines ->
            var rank = 0
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                map[trimmed] = rank++
            }
        }
        return map
    }

    /**
     * 字节 → Unicode 字符映射（HuggingFace byte_to_unicode）。
     * 将 0..255 中不可打印的控制字节映射到可打印 Unicode 区间，避免 vocab 中出现不可见字符。
     */
    private fun bytesToUnicode(): Map<Int, String> {
        val out = HashMap<Int, String>(256)
        val printable = ((0x21..0x7E) + (0xA1..0xAC) + (0xAE..0xFF)).toHashSet()
        var extra = 256
        for (b in 0..255) {
            out[b] = if (b in printable) String(Character.toChars(b))
            else String(Character.toChars(extra++))
        }
        return out
    }
}
