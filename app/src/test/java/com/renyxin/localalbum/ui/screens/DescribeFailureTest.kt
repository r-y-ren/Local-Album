package com.renyxin.localalbum.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class DescribeFailureTest {
    @Test
    fun `null or blank errors are unknown`() {
        assertEquals("未知错误", describeFailure(null))
        assertEquals("未知错误", describeFailure(""))
        assertEquals("未知错误", describeFailure("  "))
    }

    @Test
    fun `single stage batch summary expands to readable text`() {
        assertEquals(
            "语义分析 失败（该批共 19 个文件）",
            describeFailure("core:semantic:19"),
        )
        assertEquals(
            "文字识别 失败（该批共 2 个文件）",
            describeFailure("core:ocr:2"),
        )
    }

    @Test
    fun `multi stage batch summaries are split and deduplicated`() {
        assertEquals(
            "语义分析 失败（该批共 1 个文件）；人脸识别 失败（该批共 3 个文件）",
            describeFailure("core:semantic:1,core:face:3"),
        )
        assertEquals(
            "语义分析 失败（该批共 1 个文件）",
            describeFailure("core:semantic:1,core:semantic:1"),
        )
    }

    @Test
    fun `known single point error codes are translated`() {
        assertEquals("图像解码失败或模型未产出结果", describeFailure("semantic_empty_vector"))
        assertEquals("图像解码失败", describeFailure("decode_failed"))
    }

    @Test
    fun `unknown tokens pass through verbatim`() {
        assertEquals("IllegalStateException", describeFailure("IllegalStateException"))
        assertEquals(
            "missing_stages:core:face",
            describeFailure("missing_stages:core:face"),
        )
    }
}
