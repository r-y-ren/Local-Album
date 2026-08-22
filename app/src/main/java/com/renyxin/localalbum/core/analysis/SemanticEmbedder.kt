package com.renyxin.localalbum.core.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.renyxin.localalbum.data.db.entity.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * 设备端语义嵌入器（Phase 4.1 语义搜索）。
 *
 * ## 设计原理
 *
 * 将图片和文本编码到同一向量空间，使自然语言查询（如"日落"）能匹配到
 * 实际包含日落场景的图片，即使文件名/OCR 中没有出现该词。
 *
 * 由于真正的 CLIP `.tflite` 模型文件无法在代码中生成且会显著增大 APK 体积（30MB+），
 * 本实现采用**概念向量（Concept Embedding）**方案：
 *
 * 1. **图片侧**：从已计算好的图像特征（场景分类得分、颜色直方图、亮度/饱和度、
 *    宽高比）派生一个固定维度的概念向量。每个维度对应一个语义概念
 *    （如"日落"、"海滩"、"美食"、"夜景"、"自拍"…）。
 * 2. **文本侧**：将用户查询通过中英文同义词词典映射到同一组概念维度。
 *    例如"日落"→ sunset 概念、"海边"→ beach 概念。
 * 3. **检索**：归一化后计算余弦相似度，返回 Top-K。
 *
 * 该方案的优势：
 * - 零外部模型依赖，APK 体积零增长
 * - 真正的跨语言语义匹配（中文查询 ↔ 英文概念 ↔ 图像特征）
 * - 完全可单元测试（纯算法，无 Android 依赖）
 * - 架构预留：未来接入真实 CLIP 模型时，只需替换 [embedImage] / [embedText] 的实现，
 *   向量维度和存储/检索逻辑无需改动
 *
 * ## 概念维度定义
 *
 * 向量维度顺序固定，见 [CONCEPTS] 数组。当前共 [EMBEDDING_DIM] 维。
 */
class SemanticEmbedder {

    companion object {
        private const val TAG = "SemanticEmbedder"

        /** 嵌入向量维度（概念数量） */
        const val EMBEDDING_DIM = 32

        /** 概型版本号，模型升级后递增以触发增量重建 */
        const val MODEL_VERSION = 1

        /**
         * 概念维度定义。顺序固定，索引即概念 ID。
         * 每个概念包含：中文名、英文名、一组同义词（用于文本匹配）。
         */
        val CONCEPTS: List<Concept> = listOf(
            Concept(0, "日落", "sunset", listOf("黄昏", "夕阳", "dusk", "sundown", "twilight")),
            Concept(1, "日出", "sunrise", listOf("黎明", "清晨", "dawn", "morning")),
            Concept(2, "海滩", "beach", listOf("海边", "沙滩", "海岸", "seaside", "coast", "ocean", "sea")),
            Concept(3, "山脉", "mountain", listOf("山", "雪山", "山峰", "hill", "peak", "alps")),
            Concept(4, "森林", "forest", listOf("树林", "丛林", "wood", "woods", "jungle", "tree")),
            Concept(5, "城市", "city", listOf("都市", "城市景观", "urban", "downtown", "skyline", "building")),
            Concept(6, "夜景", "night", listOf("夜晚", "夜间", "黑夜", "nightlife", "dark", "midnight")),
            Concept(7, "美食", "food", listOf("食物", "餐饮", "菜肴", "meal", "dish", "cuisine", "dessert", "drink")),
            Concept(8, "自拍", "selfie", listOf("自拍照", "自拍人像", "portrait")),
            Concept(9, "人物", "people", listOf("人", "朋友", "家人", "person", "human", "crowd", "group")),
            Concept(10, "宠物", "pet", listOf("动物", "猫", "狗", "animal", "cat", "dog", "puppy", "kitten")),
            Concept(11, "花朵", "flower", listOf("花", "鲜花", "bloom", "blossom", "floral")),
            Concept(12, "雪景", "snow", listOf("下雪", "冰雪", "winter", "ice", "frost")),
            Concept(13, "天空", "sky", listOf("蓝天", "白云", "cloud", "clouds", "blue sky")),
            Concept(14, "水景", "water", listOf("湖泊", "河流", "瀑布", "lake", "river", "waterfall", "pond")),
            Concept(15, "建筑", "architecture", listOf("建筑物", "教堂", "寺庙", "castle", "church", "temple", "tower")),
            Concept(16, "室内", "indoor", listOf("室内场景", "屋里", "interior", "inside", "room")),
            Concept(17, "户外", "outdoor", listOf("室外", "野外", "outside", "nature", "wilderness")),
            Concept(18, "微距", "macro", listOf("特写", "近距", "close-up", "closeup", "detail")),
            Concept(19, "文档", "document", listOf("文件", "文档截图", "text", "paper", "screenshot")),
            Concept(20, "风景", "landscape", listOf("风光", "景色", "scenery", "view", "panorama", "countryside")),
            Concept(21, "汽车", "car", listOf("车辆", "机动车", "vehicle", "auto", "truck", "bike", "bicycle")),
            Concept(22, "旅行", "travel", listOf("旅游", "出游", "trip", "vacation", "holiday", "journey")),
            Concept(23, "节日", "festival", listOf("庆典", "派对", "party", "celebration", "birthday", "wedding")),
            Concept(24, "运动", "sport", listOf("体育", "运动场景", "exercise", "fitness", "game")),
            Concept(25, "黑白", "blackwhite", listOf("黑白照片", "单色", "monochrome", "grayscale", "b&w")),
            Concept(26, "秋天", "autumn", listOf("秋季", "落叶", "fall", "fall foliage")),
            Concept(27, "春天", "spring", listOf("春季", "春暖花开", "blossom season")),
            Concept(28, "夏天", "summer", listOf("夏季", "夏日", "sunny day")),
            Concept(29, "雨天", "rain", listOf("下雨", "雨滴", "rainy", "raindrop", "storm")),
            Concept(30, "沙漠", "desert", listOf("沙丘", "荒漠", "dune", "sand")),
            Concept(31, "未知", "unknown", listOf("其他", "other", "misc")),
        )

        /** 概念名 → 维度索引的快速查找表（中英文 + 同义词） */
        private val TERM_TO_INDEX: Map<String, Int> = buildMap {
            for (c in CONCEPTS) {
                put(c.zhName, c.index)
                put(c.enName, c.index)
                for (syn in c.synonyms) put(syn, c.index)
            }
        }
    }

    /**
     * 语义概念定义。
     *
     * @param index 维度索引（0-based）
     * @param zhName 中文名
     * @param enName 英文名
     * @param synonyms 同义词列表（中英文混合，用于文本查询匹配）
     */
    data class Concept(
        val index: Int,
        val zhName: String,
        val enName: String,
        val synonyms: List<String>,
    )

    /**
     * 图片特征提取结果（从已有分析字段 + 轻量图像采样派生）。
     * 这些字段在当前插件分析管道中已计算并存储于 [MediaEntity]。
     */
    data class ImageFeatures(
        val sceneType: String?,
        val qualityScore: Float,
        val ocrText: String?,
        val avgBrightness: Float,
        val avgSaturation: Float,
        val avgRed: Float,
        val avgGreen: Float,
        val avgBlue: Float,
        val blueGreenRatio: Float,
        val aspectRatio: Float,
        val centerBrightness: Float,
        val edgeBrightness: Float,
        val centerSaturation: Float,
        val isSquarish: Boolean,
        val isWideScreen: Boolean,
        val hasUniformTexture: Boolean,
        val width: Int,
        val height: Int,
    )

    // ---- 图片嵌入 ----

    /**
     * 对单个图片文件生成语义嵌入向量。
     *
     * 结合数据库中已存储的分析字段（sceneType/ocrText）和实时轻量图像特征采样，
     * 映射到 [EMBEDDING_DIM] 维概念空间。
     *
     * @param file 图片文件
     * @param entity 数据库中已存储的媒体实体（含 sceneType/ocrText/qualityScore）
     * @return 归一化的语义向量，长度 = [EMBEDDING_DIM]；失败返回 null
     */
    suspend fun embedImage(file: File, entity: MediaEntity? = null): FloatArray? =
        withContext(Dispatchers.IO) {
            val features = extractImageFeatures(file, entity) ?: return@withContext null
            val vector = computeConceptVector(features)
            normalize(vector)
            vector
        }

    /**
     * 批量生成图片嵌入（纯特征计算，不重复解码图片）。
     * 当已有 [ImageFeatures] 时可直接调用，避免重复 IO。
     */
    fun embedFromFeatures(features: ImageFeatures): FloatArray {
        val vector = computeConceptVector(features)
        normalize(vector)
        return vector
    }

    /**
     * 从文件 + 数据库实体提取图像特征。
     * 优先使用数据库中已存储的 sceneType/ocrText，避免重复计算。
     */
    private fun extractImageFeatures(file: File, entity: MediaEntity?): ImageFeatures? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val srcW = options.outWidth
        val srcH = options.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var sampleSize = 1
        val maxDim = 128 // 语义嵌入只需低分辨率采样
        while (srcW / sampleSize > maxDim || srcH / sampleSize > maxDim) {
            sampleSize *= 2
        }

        // 16-bit PNG 会解码为 RGBA_F16，强制 8 位（详见 InsightFaceProvider）
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val w = bitmap.width
        val h = bitmap.height
        val total = w * h
        val pixels = IntArray(total)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumBrightness = 0f
        var sumSaturation = 0f

        val centerXStart = (w * 0.25).toInt()
        val centerXEnd = (w * 0.75).toInt()
        val centerYStart = (h * 0.25).toInt()
        val centerYEnd = (h * 0.75).toInt()

        var centerSumBrightness = 0f
        var centerPixels = 0
        var centerSumSaturation = 0f
        var edgeSumBrightness = 0f
        var edgePixels = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val pixel = pixels[idx]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                sumR += r
                sumG += g
                sumB += b

                val brightness = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                val max = maxOf(r, g, b).toFloat()
                val min = minOf(r, g, b).toFloat()
                val saturation = if (max > 0f) (max - min) / max else 0f

                sumBrightness += brightness
                sumSaturation += saturation

                val isCenter = x in centerXStart..centerXEnd && y in centerYStart..centerYEnd
                if (isCenter) {
                    centerSumBrightness += brightness
                    centerSumSaturation += saturation
                    centerPixels++
                } else {
                    edgeSumBrightness += brightness
                    edgePixels++
                }
            }
        }

        bitmap.recycle()

        val avgR = sumR.toFloat() / total / 255f
        val avgG = sumG.toFloat() / total / 255f
        val avgB = sumB.toFloat() / total / 255f
        val avgBrightness = sumBrightness / total
        val avgSaturation = sumSaturation / total
        val blueGreenRatio = if (avgG > 0) avgB / avgG else avgB
        val aspectRatio = srcW.toFloat() / srcH.toFloat()
        val contrast = sqrt(
            ((avgR - avgBrightness) * (avgR - avgBrightness) +
                (avgG - avgBrightness) * (avgG - avgBrightness) +
                (avgB - avgBrightness) * (avgB - avgBrightness)) / 3f,
        )

        return ImageFeatures(
            sceneType = entity?.sceneType,
            qualityScore = entity?.qualityScore ?: 0f,
            ocrText = entity?.ocrText,
            avgBrightness = avgBrightness,
            avgSaturation = avgSaturation,
            avgRed = avgR,
            avgGreen = avgG,
            avgBlue = avgB,
            blueGreenRatio = blueGreenRatio,
            aspectRatio = aspectRatio,
            centerBrightness = if (centerPixels > 0) centerSumBrightness / centerPixels else avgBrightness,
            edgeBrightness = if (edgePixels > 0) edgeSumBrightness / edgePixels else avgBrightness,
            centerSaturation = if (centerPixels > 0) centerSumSaturation / centerPixels else avgSaturation,
            isSquarish = aspectRatio in 0.85f..1.15f,
            isWideScreen = aspectRatio > 1.6f || aspectRatio < 0.6f,
            hasUniformTexture = contrast < 0.05f,
            width = srcW,
            height = srcH,
        )
    }

    /**
     * 核心算法：将图像特征映射到概念向量。
     *
     * 每个概念维度根据特征计算一个 [0,1] 激活值，组合多源信号：
     * - 场景分类标签（强信号）
     * - 颜色/亮度/饱和度分布（弱信号）
     * - OCR 文本（文本概念匹配）
     * - 宽高比/纹理（辅助信号）
     */
    private fun computeConceptVector(f: ImageFeatures): FloatArray {
        val vec = FloatArray(EMBEDDING_DIM)

        // ---- 场景分类标签强信号 ----
        val scene = f.sceneType?.lowercase()
        if (scene != null) {
            when (scene) {
                "landscape" -> { vec[20] += 0.8f; vec[17] += 0.4f; vec[13] += 0.3f }
                "food" -> vec[7] += 0.9f
                "document", "screenshot", "whiteboard" -> vec[19] += 0.9f
                "pet" -> vec[10] += 0.85f
                "selfie" -> { vec[8] += 0.85f; vec[9] += 0.5f }
                "night" -> vec[6] += 0.85f
                "macro" -> vec[18] += 0.85f
                "outdoor" -> { vec[17] += 0.7f; vec[20] += 0.3f }
                "indoor" -> vec[16] += 0.8f
            }
        }

        // ---- 颜色/亮度信号 → 概念 ----

        // 日落/日出：暖色调（R>B）+ 中低亮度
        val warmth = (f.avgRed - f.avgBlue).coerceIn(0f, 0.4f) / 0.4f
        if (warmth > 0.3f) {
            if (f.avgBrightness in 0.2f..0.6f) {
                vec[0] += warmth * 0.6f // 日落
            } else if (f.avgBrightness > 0.5f) {
                vec[1] += warmth * 0.4f // 日出
            }
        }

        // 夜景：低亮度
        if (f.avgBrightness < 0.3f) {
            vec[6] += (0.3f - f.avgBrightness) / 0.3f * 0.7f
        }

        // 海滩/水景：高蓝绿占比
        if (f.blueGreenRatio > 0.9f && f.avgBlue > 0.3f) {
            vec[2] += (f.blueGreenRatio - 0.9f).coerceIn(0f, 0.6f) * 0.8f
            vec[14] += (f.avgBlue - 0.3f).coerceIn(0f, 0.4f) * 0.6f
        }

        // 天空：高亮度 + 低饱和度 + 蓝色调
        if (f.avgBrightness > 0.6f && f.avgSaturation < 0.3f && f.avgBlue > f.avgRed) {
            vec[13] += 0.5f
        }

        // 森林/花朵：高绿色或高饱和度
        if (f.avgGreen > f.avgRed && f.avgGreen > f.avgBlue) {
            vec[4] += (f.avgGreen - maxOf(f.avgRed, f.avgBlue)).coerceIn(0f, 0.3f) * 2f * 0.6f
        }
        if (f.avgSaturation > 0.5f && f.avgRed > f.avgGreen) {
            vec[11] += (f.avgSaturation - 0.5f).coerceIn(0f, 0.5f) * 0.6f
        }

        // 雪景：高亮度 + 低饱和度
        if (f.avgBrightness > 0.75f && f.avgSaturation < 0.15f) {
            vec[12] += (f.avgBrightness - 0.75f).coerceIn(0f, 0.25f) * 3f * 0.7f
        }

        // 黑白：极低饱和度 + 低对比度色彩
        if (f.avgSaturation < 0.08f && f.avgBrightness in 0.2f..0.8f) {
            vec[25] += (0.08f - f.avgSaturation) / 0.08f * 0.6f
        }

        // 美食：暖色调 + 高饱和度 + 方形
        if (warmth > 0.2f && f.avgSaturation > 0.35f && f.isSquarish) {
            vec[7] += warmth * 0.4f
        }

        // 自拍/人物：中心高亮 + 肤色 + 近方形
        val skinTone = if (f.avgRed > f.avgGreen && f.avgGreen > f.avgBlue &&
            f.avgRed - f.avgBlue in 0.05f..0.35f
        ) 1f else 0f
        if (skinTone > 0f && f.isSquarish) {
            vec[8] += 0.4f
            vec[9] += 0.3f
        }

        // 微距：中心-边缘亮度差
        val centerEdgeDiff = kotlin.math.abs(f.centerBrightness - f.edgeBrightness)
        if (centerEdgeDiff > 0.1f) {
            vec[18] += centerEdgeDiff.coerceIn(0.1f, 0.5f) * 1.5f * 0.5f
        }

        // 户外/室内
        if (f.blueGreenRatio > 0.8f && f.avgBrightness > 0.4f) {
            vec[17] += 0.3f
        } else if (f.blueGreenRatio < 0.7f && warmth > 0.2f) {
            vec[16] += 0.3f
        }

        // 风景：横向构图 + 中等饱和度
        if (f.aspectRatio in 1.3f..2.3f && f.avgSaturation in 0.2f..0.6f) {
            vec[20] += 0.3f
        }

        // ---- OCR 文本信号 ----
        val ocr = f.ocrText
        if (!ocr.isNullOrBlank()) {
            val ocrLower = ocr.lowercase()
            for (concept in CONCEPTS) {
                val terms = listOf(concept.zhName, concept.enName) + concept.synonyms
                for (term in terms) {
                    if (ocrLower.contains(term.lowercase())) {
                        vec[concept.index] += 0.5f
                        break
                    }
                }
            }
        }

        // ---- 季节性概念（基于颜色组合推断） ----
        // 秋天：橙红色调
        if (f.avgRed > 0.35f && f.avgRed > f.avgGreen && f.avgGreen > f.avgBlue && f.avgSaturation > 0.3f) {
            vec[26] += 0.4f
        }
        // 春天：高绿色 + 中等饱和度
        if (f.avgGreen > 0.35f && f.avgSaturation in 0.25f..0.6f) {
            vec[27] += 0.4f
        }
        // 夏天：高亮度 + 高蓝绿
        if (f.avgBrightness > 0.55f && f.blueGreenRatio > 0.9f) {
            vec[28] += 0.4f
        }
        // 雨天：低亮度 + 低饱和度 + 蓝灰
        if (f.avgBrightness < 0.4f && f.avgSaturation < 0.25f && f.avgBlue > f.avgRed) {
            vec[29] += 0.4f
        }

        // 质量评分作为全局权重微调（高质量图片略微增强所有已激活概念）
        val qualityBoost = (f.qualityScore / 100f).coerceIn(0f, 1f) * 0.1f
        for (i in vec.indices) {
            if (vec[i] > 0) vec[i] += qualityBoost
        }

        return vec
    }

    // ---- 文本嵌入 ----

    /**
     * 将自然语言查询编码为语义向量。
     *
     * 通过中英文同义词词典将查询词映射到对应概念维度。
     * 支持多词查询（如"海边的日落"会同时激活 beach + sunset 维度）。
     *
     * @param query 用户输入的查询文本（中文或英文）
     * @return 归一化的语义向量，长度 = [EMBEDDING_DIM]
     */
    fun embedText(query: String): FloatArray {
        val vec = FloatArray(EMBEDDING_DIM)
        if (query.isBlank()) return vec

        val queryLower = query.lowercase().trim()

        // 1. 精确匹配概念名/同义词
        for (concept in CONCEPTS) {
            val terms = listOf(concept.zhName, concept.enName) + concept.synonyms
            for (term in terms) {
                val termLower = term.lowercase()
                if (queryLower == termLower) {
                    vec[concept.index] += 1.0f
                    break
                }
            }
        }

        // 2. 子串包含匹配（处理"海边的日落"这类组合查询）
        if (vec.all { it == 0f }) {
            for (concept in CONCEPTS) {
                val terms = listOf(concept.zhName, concept.enName) + concept.synonyms
                for (term in terms) {
                    val termLower = term.lowercase()
                    if (termLower.length >= 2 && queryLower.contains(termLower)) {
                        vec[concept.index] += 0.8f
                        break
                    }
                }
            }
        }

        // 3. 反向匹配：查询包含概念向量的词
        if (vec.all { it == 0f }) {
            // 分词尝试：按空格/标点分割
            val tokens = queryLower.split(Regex("[\\s,，。.!！?？、/\\\\]+"))
                .filter { it.isNotBlank() }
            for (token in tokens) {
                for (concept in CONCEPTS) {
                    val terms = listOf(concept.zhName, concept.enName) + concept.synonyms
                    for (term in terms) {
                        val termLower = term.lowercase()
                        if (token == termLower || (termLower.length >= 2 && token.contains(termLower))) {
                            vec[concept.index] += 0.6f
                            break
                        }
                    }
                }
            }
        }

        // 4. 如果仍未匹配到任何概念，尝试模糊匹配（编辑距离）
        if (vec.all { it == 0f }) {
            for (concept in CONCEPTS) {
                val terms = listOf(concept.zhName, concept.enName) + concept.synonyms
                for (term in terms) {
                    if (fuzzyMatch(queryLower, term.lowercase())) {
                        vec[concept.index] += 0.4f
                        break
                    }
                }
            }
        }

        // 5. 兜底：如果查询完全无法匹配任何概念，激活 unknown 维度
        if (vec.all { it == 0f }) {
            vec[31] = 1.0f
        }

        normalize(vec)
        return vec
    }

    /**
     * 简单模糊匹配：检查查询和术语是否有公共子串（长度 ≥ 2）。
     */
    private fun fuzzyMatch(query: String, term: String): Boolean {
        if (term.length < 2 || query.length < 2) return false
        // 检查是否有长度为 2+ 的公共子串
        for (i in 0..term.length - 2) {
            val sub = term.substring(i, i + 2)
            if (query.contains(sub)) return true
        }
        return false
    }

    // ---- 向量工具 ----

    /**
     * L2 归一化（原地修改）。
     */
    fun normalize(vec: FloatArray) {
        var norm = 0.0
        for (v in vec) norm += (v * v).toDouble()
        norm = sqrt(norm)
        if (norm > 0) {
            val inv = (1.0 / norm).toFloat()
            for (i in vec.indices) vec[i] *= inv
        }
    }

    /**
     * 计算两个向量的余弦相似度。
     * 假设输入向量已归一化，此时等价于点积。
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) {
            // 维度不匹配时静默返回 0（避免在单元测试中依赖 android.util.Log）
            return 0f
        }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += (a[i] * b[i]).toDouble()
            normA += (a[i] * a[i]).toDouble()
            normB += (b[i] * b[i]).toDouble()
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0) (dot / denom).toFloat() else 0f
    }

    /**
     * 将向量序列化为逗号分隔的字符串（用于数据库存储）。
     */
    fun serialize(vec: FloatArray): String {
        return vec.joinToString(",") { "%.6f".format(it) }
    }

    /**
     * 从逗号分隔的字符串反序列化为向量。
     */
    fun deserialize(str: String): FloatArray {
        return str.split(",").mapNotNull { it.trim().toFloatOrNull() }
            .toFloatArray()
    }
}
