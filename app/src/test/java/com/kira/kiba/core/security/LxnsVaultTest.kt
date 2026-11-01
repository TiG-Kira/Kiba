package com.kira.kiba.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落雪凭据保险箱的端到端自检。
 *
 * 这里验证的是**完整链路**：构建期 sealLxnsCredentials 生成的加扰分片载荷 →
 * 运行时 [LxnsVault] 还原顺序 / 反加扰 / AES-GCM 解密 / 明文摘要校验 → 解析出三件凭据。
 *
 * 之所以值得单测：这条链路任何一环写错（置换方向反了、加扰常量差一位、
 * GCM tag 长度、摘要比较），表现都是「线上静默关闭开发者通道」——
 * 用户只会看到「官方直传不可用，已回落本地解析」，不会报错，极难发现。
 *
 * 未注入凭据的构建（公开仓库默认、无 Secrets 的 CI）载荷为 EMPTY，直接跳过。
 */
class LxnsVaultTest {

    @Test
    fun `sealed payload restores credentials end to end`() {
        if (LxnsSealData.EMPTY) {
            // 没有注入凭据：通道按设计关闭，没有可验证的内容
            assertFalse(LxnsVault.hasDevKey())
            assertFalse(LxnsVault.hasOAuth())
            return
        }

        val dev = LxnsVault.devKey()
        val id = LxnsVault.oauthClientId()
        val secret = LxnsVault.oauthClientSecret()

        assertTrue("开发者密钥不应为空", dev.isNotBlank())
        assertTrue("OAuth 应用 ID 不应为空", id.isNotBlank())
        assertTrue("OAuth 应用密钥不应为空", secret.isNotBlank())

        // 解出来的必须是「干净的一行值」：带换行 / 带 KV 前缀说明解析或密封格式对不上
        assertEquals(dev.trim(), dev)
        assertEquals(id.trim(), id)
        assertEquals(secret.trim(), secret)
        assertFalse(dev.contains("dev="))
        assertFalse(id.contains("id="))

        assertTrue(LxnsVault.hasDevKey())
        assertTrue(LxnsVault.hasOAuth())
    }

    @Test
    fun `seal arrays are permuted not stored in order`() {
        if (LxnsSealData.EMPTY) return
        val perm = LxnsSealData.PERM
        // 置换表必须是 0..n-1 的一个排列（乱序写错会让还原出来的 blob 整体错位）
        assertEquals(LxnsSealData.DATA.size, perm.size)
        assertEquals(perm.size, perm.toSet().size)
        assertEquals((0 until perm.size).toSet(), perm.toSet())
        // 且不能是恒等排列，否则「乱序」形同虚设
        assertFalse(perm.withIndex().all { (i, v) -> i == v })
    }
}
