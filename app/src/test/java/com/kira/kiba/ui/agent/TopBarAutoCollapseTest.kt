package com.kira.kiba.ui.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送消息后自动贴底时的顶栏折叠判定：
 * 内容不足一屏一律保持原状，只有底部真的超出屏幕才折叠；
 * 顶栏没有登记折叠量（材质关闭 / 尚未测量）时不能写入无效状态。
 */
class TopBarAutoCollapseTest {

    @Test
    fun `底部没超出屏幕时顶栏保持当前状态`() {
        assertFalse(shouldCollapseTopBarAfterScrollToBottom(canScrollBackward = false, heightOffsetLimit = -120f))
    }

    @Test
    fun `底部超出屏幕时触发折叠`() {
        assertTrue(shouldCollapseTopBarAfterScrollToBottom(canScrollBackward = true, heightOffsetLimit = -120f))
    }

    @Test
    fun `材质关闭时顶栏没有折叠量就跳过`() {
        assertFalse(shouldCollapseTopBarAfterScrollToBottom(canScrollBackward = true, heightOffsetLimit = 0f))
    }

    @Test
    fun `内容不足一屏且没有折叠量时保持原状`() {
        assertFalse(shouldCollapseTopBarAfterScrollToBottom(canScrollBackward = false, heightOffsetLimit = 0f))
    }

    @Test
    fun `顶栏刚测量出极小折叠量也算可折叠`() {
        assertTrue(shouldCollapseTopBarAfterScrollToBottom(canScrollBackward = true, heightOffsetLimit = -0.5f))
    }
}
