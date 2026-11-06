package com.kira.kiba.core.best50

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 单曲 ra 系数表回归测试。
 *
 * 表值来自机台口径（与上游 MaimaiData `ConvertUtils.achievementToRating` 逐档一致）。
 * 2.3.0 之前整张表错位一档（S 只给 16.8、SSS 只给 21.1、≥100.5 只给 21.6），
 * 导致「机台上分 → 上传 → Best 50 里 ra 与机台对不上」。这里把关键档位钉死。
 */
class Best50RaTest {

    private fun ra(ds: Double, achievements: Double) =
        Best50ImageBuilder.computeRa(ds, achievements, true)

    @Test
    fun `最高档按 100_5 封顶`() {
        // 13.5 × 1.005 × 22.4 = 303.912 → 303
        assertEquals(303, ra(13.5, 100.5))
        // 超过 100.5 不再涨
        assertEquals(303, ra(13.5, 101.0))
    }

    @Test
    fun `SSS 与 SS 的边界档位`() {
        // 14.0 × 1.000 × 21.6 = 302.4 → 302
        assertEquals(302, ra(14.0, 100.0))
        // 100.4999 单独一档 22.2：14.0 × 1.004999 × 22.2 ≈ 312.35 → 312
        assertEquals(312, ra(14.0, 100.4999))
        // 99.9999 单独一档 21.4：14.0 × 0.999999 × 21.4 ≈ 299.59 → 299
        assertEquals(299, ra(14.0, 99.9999))
    }

    @Test
    fun `S 档必须回到 20_0 而不是旧的 16_8`() {
        // 13.0 × 0.975 × 20.0 = 253.5 → 253（旧实现给 212，差 41）
        assertEquals(253, ra(13.0, 97.5))
        // 96.9999 落到 17.6：13.0 × 0.969999 × 17.6 = 221.9 → 221
        assertEquals(221, ra(13.0, 96.9999))
        // 98.0 起是 20.3
        assertEquals(258, ra(13.0, 98.0))
    }

    @Test
    fun `AAA 与 AA 边界`() {
        // 12.0 × 0.94 × 16.8 = 189.5 → 189
        assertEquals(189, ra(12.0, 94.0))
        // 90.0 → 15.2：12.0 × 0.9 × 15.2 = 164.16 → 164
        assertEquals(164, ra(12.0, 90.0))
    }

    @Test
    fun `低达成率与异常输入`() {
        // 10% 以下才是 0；40%~50% 有机台保底档 6.4（12.0 × 0.499 × 6.4 = 38.3 → 38）
        assertEquals(0, ra(12.0, 9.9))
        assertEquals(38, ra(12.0, 49.9))
        assertEquals(0, ra(12.0, 0.0))
        // 定数缺失（曲库没同步）时不应该算出 ra，避免把 B50 拉穿
        assertEquals(0, ra(0.0, 100.5))
    }

    @Test
    fun `SD 与 DX 共用同一张表`() {
        // spp 参数只为兼容旧调用点，不改变结果
        assertEquals(
            Best50ImageBuilder.computeRa(13.0, 99.0, true),
            Best50ImageBuilder.computeRa(13.0, 99.0, false),
        )
    }
}
