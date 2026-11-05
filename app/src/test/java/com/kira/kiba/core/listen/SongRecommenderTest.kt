package com.kira.kiba.core.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SongRecommender 推荐口径单测：画像提取 / 收藏优先 / 冷启动 / 条数上限。
 */
class SongRecommenderTest {

    private fun song(id: Int, genre: String, version: Int? = null) =
        SongRecommender.SongLite(id, genre, version)

    @Test
    fun `推荐不超过上限`() {
        val songs = (1..50).map { song(it, "流行") }
        val result = SongRecommender.recommend(songs, emptyList(), emptySet(), seed = 42L)
        assertEquals(SongRecommender.LIMIT, result.size)
    }

    @Test
    fun `空曲库返回空`() {
        assertTrue(SongRecommender.recommend(emptyList(), emptyList(), emptySet(), seed = 1L).isEmpty())
    }

    @Test
    fun `收藏歌曲排在前面`() {
        val songs = (1..40).map { song(it, "东方") } + listOf(song(101, "小众流派"))
        // 收藏 101（唯一小众流派歌），画像是东方——但收藏权重 6 应把它顶到前排
        val result = SongRecommender.recommend(
            songs = songs,
            records = emptyList(),
            favoriteIds = setOf(101),
            seed = 7L,
        )
        assertEquals(101, result.first())
    }

    @Test
    fun `画像流派获得更高权重`() {
        // 用户 Best 40 全是「流行」：流行歌应整体排在从未出现过的「爵士」前面
        val records = (1..40).map { SongRecommender.RecordLite(it, ra = 1500 - it, genre = "流行") }
        val candidates = (1001..1005).map { song(it, "爵士") } + (2001..2005).map { song(it, "流行") }
        val result = SongRecommender.recommend(candidates, records, emptySet(), seed = 9L)
        val firstNonPop = result.indexOfFirst { id -> id in 1001..1005 }
        // 至少有一首流行排在所有爵士前面（同档随机扰动不应整体翻盘）
        assertTrue("流行应整体靠前，实际顺序=$result", firstNonPop >= 3)
    }

    @Test
    fun `冷启动均匀随机不崩溃`() {
        val songs = (1..30).map { song(it, "流派$it") }
        val r1 = SongRecommender.recommend(songs, emptyList(), emptySet(), seed = 100L)
        val r2 = SongRecommender.recommend(songs, emptyList(), emptySet(), seed = 200L)
        assertEquals(SongRecommender.LIMIT, r1.size)
        // 不同种子洗牌结果不同（随机扰动生效）
        assertTrue(r1 != r2)
    }

    @Test
    fun `推荐不含重复曲目`() {
        val songs = (1..30).map { song(it, "流行") }
        val result = SongRecommender.recommend(songs, emptyList(), setOf(1, 2, 3), seed = 5L)
        assertEquals(result.size, result.toSet().size)
    }
}
