package com.renyxin.localalbum.core.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnorePatternMatcherTest {

    @Test
    fun `compile skips blank and illegal patterns`() {
        val compiled = IgnorePatternMatcher.compile(listOf(
            "^.trash",      // 合法
            "   ",          // 空白
            "",             // 空
            "[unclosed",    // 非法正则
            "backup$",      // 合法
        ))
        // 仅两条合法规则被编译
        assertEquals(2, compiled.size)
    }

    @Test
    fun `compile empty list returns empty`() {
        assertTrue(IgnorePatternMatcher.compile(emptyList()).isEmpty())
    }

    @Test
    fun `matchesAny anchors with caret for filename prefix`() {
        val compiled = IgnorePatternMatcher.compile(listOf("^.trash"))
        // 以 .trash 开头的文件名命中
        assertTrue(IgnorePatternMatcher.matchesAny(".trash_img.jpg", compiled))
        assertTrue(IgnorePatternMatcher.matchesAny(".trash", compiled))
        // 不以 .trash 开头的不命中
        assertFalse(IgnorePatternMatcher.matchesAny("img.trash.jpg", compiled))
        assertFalse(IgnorePatternMatcher.matchesAny("normal.jpg", compiled))
    }

    @Test
    fun `matchesAny does partial match without anchors`() {
        val compiled = IgnorePatternMatcher.compile(listOf("temp"))
        assertTrue(IgnorePatternMatcher.matchesAny("temp_file.jpg", compiled))
        assertTrue(IgnorePatternMatcher.matchesAny("my_temp_dir", compiled))
        assertFalse(IgnorePatternMatcher.matchesAny("normal.jpg", compiled))
    }

    @Test
    fun `matchesAny with empty patterns returns false`() {
        assertFalse(IgnorePatternMatcher.matchesAny(".trash.jpg", emptyList()))
    }

    @Test
    fun `matchesAny hits directory names`() {
        val compiled = IgnorePatternMatcher.compile(listOf("^backup"))
        assertTrue(IgnorePatternMatcher.matchesAny("backup_photos", compiled))
        assertFalse(IgnorePatternMatcher.matchesAny("photos_backup", compiled))
    }

    @Test
    fun `multiple patterns any match`() {
        val compiled = IgnorePatternMatcher.compile(listOf("^.trash", "\\.tmp$"))
        assertTrue(IgnorePatternMatcher.matchesAny(".trash_x", compiled))
        assertTrue(IgnorePatternMatcher.matchesAny("edit.tmp", compiled))
        assertFalse(IgnorePatternMatcher.matchesAny("keep.jpg", compiled))
    }
}
