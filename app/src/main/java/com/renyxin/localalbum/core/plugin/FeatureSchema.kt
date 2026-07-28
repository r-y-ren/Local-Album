package com.renyxin.localalbum.core.plugin

/**
 * 异构特征数据的 Schema 描述。
 *
 * 用于描述由外部未知模型生成的任意维度特征向量、自定义分类标签、
 * 特殊坐标等异构数据的结构，使存储层无需依赖固定表结构。
 *
 * 每个 [FeatureSchema] 描述一个插件输出的一组字段，每个字段有明确的
 * 数据类型和维度信息，序列化为 JSON 后与数据一同持久化到 feature_store 表。
 *
 * @property pluginId 生成该特征的插件 ID
 * @property featureType 特征类型分类（如 "embedding"、"classification"、"detection_box"）
 * @property fields 字段定义列表，描述每个维度的名称、类型与含义
 * @property modelVersion 生成该特征所用模型的版本号，用于模型升级后增量重建
 */
data class FeatureSchema(
    val pluginId: String,
    val featureType: String,
    val fields: List<FieldSpec>,
    val modelVersion: Int = 1,
) {

    /**
     * 单个字段（维度）的规格定义。
     *
     * @property name 字段名（如 "embedding"、"label"、"box_left"）
     * @property dataType 数据类型
     * @property dims 维度信息；标量为 null，向量为 [dim1]，矩阵为 [dim1, dim2]
     * @property description 人类可读的字段描述（可选）
     */
    data class FieldSpec(
        val name: String,
        val dataType: DataType,
        val dims: List<Int>? = null,
        val description: String? = null,
    ) {
        /**
         * 该字段的总元素数。标量返回 1，向量返回 dim1，矩阵返回 dim1*dim2。
         */
        val elementCount: Int
            get() = if (dims.isNullOrEmpty()) 1 else dims.reduce(Int::times)
    }

    /**
     * 支持的数据类型枚举。
     * 覆盖常见模型输出类型，异构数据以 JSON Blob 存储，类型用于编解码校验。
     */
    enum class DataType {
        FLOAT,
        DOUBLE,
        INT,
        LONG,
        STRING,
        BOOLEAN,
        FLOAT_ARRAY,
        INT_ARRAY,
        STRING_ARRAY,
    }

    /**
     * 快速判断该 schema 是否为向量类型（仅含一个 FLOAT_ARRAY 字段）。
     */
    val isVectorType: Boolean
        get() = fields.size == 1 && fields[0].dataType == DataType.FLOAT_ARRAY

    /**
     * 向量维度（仅当 [isVectorType] 为 true 时有意义）。
     */
    val vectorDim: Int?
        get() = if (isVectorType) fields[0].dims?.firstOrNull() else null
}
