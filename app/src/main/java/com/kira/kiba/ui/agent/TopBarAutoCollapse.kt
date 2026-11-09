package com.kira.kiba.ui.agent

/**
 * 「发送消息后自动贴底」时顶栏该不该折叠 —— 纯判定，抽出来便于单测。
 *
 * 口径（用户定稿）：
 * - 页面最底部还没超出屏幕（内容不足一屏，列表滚不动）→ 顶栏**保持当前状态**。
 *   注意是「保持」而不是「强制展开」：用户若本来就在折叠态，也不能被自动弹回去。
 * - 底部超出屏幕（贴底后列表仍能往回滚，即内容总高 > 视口高）→ 触发折叠，
 *   把大标题让出来多露一屏聊天。
 *
 * 为什么不直接算「内容总高 > 视口高」：LazyColumn 是懒加载，layoutInfo 里只有
 * 可见项，未组合的 item 高度拿不到。贴底时 `canScrollBackward` 恰好等价于
 * 「列表曾经滚离过顶部」，也就是底部原本在屏幕外，用它判定无需测量全部 item。
 * contentPadding 顶部那点 clearance 不产生额外滚动范围，不会把短对话误判成溢出。
 *
 * @param canScrollBackward 贴底后列表是否还能向上（往回）滚。
 * @param heightOffsetLimit 顶栏登记的折叠量（负值）。材质关闭 / 顶栏尚未测量时为 0，
 *                          此时没有可折叠的大标题，直接跳过，避免写入无效状态。
 */
internal fun shouldCollapseTopBarAfterScrollToBottom(
    canScrollBackward: Boolean,
    heightOffsetLimit: Float,
): Boolean = canScrollBackward && heightOffsetLimit < 0f
