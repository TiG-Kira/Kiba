package com.kira.kiba.core.host

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import top.yukonga.miuix.kmp.basic.ScrollBehavior

/**
 * 应用本地扩展宿主（公共契约层，纯接口无实现）。
 *
 * 设计目标：为「本地模块」（见 settings.gradle.kts 中由 local.properties 的
 * `kiba.extraModules` 注入的可选模块）提供一组**完全中性**的挂接点。
 *
 * 铁律：
 * - 本文件与各挂接点**不得出现任何具体功能语义**——宿主只知道「有没有扩展注册进来」，
 *   不知道扩展会做什么；没有扩展注册时所有入口返回空/否/false，应用行为与不装时完全一致。
 * - 扩展通过 Android ContentProvider 自动初始化机制在进程启动时调用 [register]，
 *   宿主不做任何主动发现、不持久化任何扩展信息。
 * - 一切能力协商都是运行时的：签名 / 环境 / 授权校验完全由扩展自行决定并静默处理，
 *   宿主对扩展的「拒绝服务」不做任何提示（无弹窗、无日志、无 UI 残留）。
 * - 宿主内置实现（曲库检索 / 成绩发布等）由 app 模块的 `HostDefaults` 装配，本层不含实现。
 */
object AppExtensions {

    // ==================== 数据传输对象（宿主 ↔ 扩展 的中性 DTO） ====================

    /** 成绩条目（与本地 Room record 表字段一一对应，便于宿主直接落库）。 */
    data class ExtRecord(
        val songId: Int,
        val title: String,
        val levelIndex: Int,
        val level: String,
        val ds: Double,
        val dxScore: Int,
        val fc: String,
        val fs: String,
        val achievements: Double,
        val ra: Int,
        val rate: String,
        val type: String,
    )

    /** 曲库检索结果（song_data 表投影）。 */
    data class ExtSong(
        val id: Int,
        val title: String,
        val artist: String,
        val isNew: Boolean,
        val type: String,
        val genre: String,
    )

    /** 谱面信息（chart 表投影：定数 / 等级标签）。 */
    data class ExtChartInfo(
        val songId: Int,
        val levelIndex: Int,
        val ds: Double,
        val level: String,
    )

    // ==================== 扩展注册与生命周期 ====================

    /**
     * 扩展提供方。扩展模块在自身 ContentProvider.onCreate 里调用 [register] 注册。
     * 所有方法都必须容错（宿主不 catch，扩展自己兜底），并且**必须在未授权时返回空值**。
     */
    interface Provider {
        /** Application.onCreate 末尾回调（SpUtil / DB 均已就绪）。 */
        fun onApplicationInit(app: Application) {}

        /**
         * 关于页 Logo 点击。返回 true 表示本次点击被消费（宿主不再处理）。
         * 连点计数等状态由扩展自持。
         */
        fun onAboutLogoTap(context: Context): Boolean = false

        /** 额外的成绩源选项（追加在 [ScoreSource.entries] 之后）。未授权返回空列表。 */
        fun extraScoreSources(game: GameType): List<ExtSource> = emptyList()

        /** 当前生效的额外成绩源；null 表示无（常规源生效）。 */
        fun activeExtraSource(game: GameType): ExtSource? = null

        /** 用户在源选择器里选了第 [index] 个额外源。 */
        fun onExtraSourcePicked(game: GameType, index: Int) {}

        /** 用户选回了常规源（扩展源被切出，扩展应停用自身的替代模式）。 */
        fun onRegularSourcePicked(game: GameType) {}

        /**
         * 常规源 UI 是否被额外源遮蔽（为 true 时账户页隐藏常规源的登录 / 更新入口）。
         */
        fun regularSourceMasked(game: GameType): Boolean = false

        /**
         * 成绩刷新分发：返回 true 表示本游戏的成绩刷新由扩展接管（宿主跳过自身逻辑）。
         */
        fun consumeRecordsRefresh(game: GameType): Boolean = false

        /** 账户页查分器组的扩展条目。未授权返回空列表。 */
        fun meEntries(game: GameType): List<ExtEntry> = emptyList()

        /** 账户页扩展条目点击。 */
        fun onMeEntry(game: GameType, id: String) {}

        /**
         * 扩展页面内容。[ExtPage(id)] 渲染时回调；返回 null（未授权 / 未知 id）时
         * 宿主渲染空占位。返回的 composable 由扩展全屏自管（含顶栏返回行为）。
         */
        fun extPageContent(id: String): (@Composable (ScrollBehavior) -> Unit)? = null
    }

    data class ExtSource(
        val key: String,
        val label: String,
        val summary: String,
    )

    data class ExtEntry(
        val id: String,
        val title: String,
        val summary: String,
        /** false = 入口置灰不可点（实现方按需说明原因）。 */
        val enabled: Boolean = true,
    )

    @Volatile
    private var provider: Provider? = null

    /** 扩展注册（幂等：后注册者覆盖前者，进程内只会有一个扩展提供方）。 */
    fun register(p: Provider) {
        provider = p
    }

    private inline fun <T> call(default: T, block: Provider.() -> T): T =
        runCatching { provider?.block() }.getOrNull() ?: default

    // ==================== 宿主能力（应用 → 扩展 单向供给） ====================

    /** 宿主向扩展开放的通用能力（全部可选，未设置即不可用）。 */
    interface HostServices {
        /** 曲库按标题检索（score 查询用）。 */
        suspend fun songSearch(query: String, limit: Int = 50): List<ExtSong> = emptyList()

        /** 按 id 取歌曲；null 表示不存在。 */
        suspend fun songById(songId: Int): ExtSong? = null

        /** 按 (songId, levelIndex) 取谱面定数 / 等级标签；null 表示无此谱面。 */
        suspend fun chartInfo(songId: Int, levelIndex: Int): ExtChartInfo? = null

        /**
         * 发布外部成绩（宿主实现：走与常规源相同的落库 / B50 / 账户状态管线）。
         * @param historySourceKey Rating 历史分桶的源 key（null 不记历史）
         */
        suspend fun publishRecords(
            records: List<ExtRecord>,
            nickname: String,
            historySourceKey: String? = null,
        ) {
        }

        /** 外部通道失败上报（宿主实现：只更新账户状态错误文案）。 */
        fun publishError(message: String) {}

        /** 当前常规成绩源的持久化 key（扩展源切换前记录用）；null 表示不可用。 */
        fun rawScoreSourceKey(game: GameType): String? = null

        /** 宿主级轻提示。 */
        fun toast(context: Context, message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @Volatile
    var host: HostServices? = null

    /** 页面导航：扩展请求打开扩展子页（宿主自行映射到内部路由）。 */
    @Volatile
    var pageOpener: ((id: String, title: String) -> Unit)? = null

    /** 宿主级对话框（扩展在非 UI 场景请求弹窗，如刷新失败引导）。 */
    @Volatile
    var hostDialog: ((title: String, message: String) -> Unit)? = null

    // ==================== 挂接点（应用代码调用） ====================

    fun notifyApplicationInit(app: Application) {
        call(Unit) { onApplicationInit(app) }
    }

    /** 关于页 Logo 点击；返回 true 表示被扩展消费。 */
    fun onAboutLogoTap(context: Context): Boolean = call(false) { onAboutLogoTap(context) }

    fun extraScoreSources(game: GameType): List<ExtSource> =
        call(emptyList()) { extraScoreSources(game) }

    fun activeExtraSource(game: GameType): ExtSource? = call(null) { activeExtraSource(game) }

    fun onExtraSourcePicked(game: GameType, index: Int) {
        call(Unit) { onExtraSourcePicked(game, index) }
    }

    fun onRegularSourcePicked(game: GameType) {
        call(Unit) { onRegularSourcePicked(game) }
    }

    fun regularSourceMasked(game: GameType): Boolean =
        call(false) { regularSourceMasked(game) }

    fun consumeRecordsRefresh(game: GameType): Boolean =
        call(false) { consumeRecordsRefresh(game) }

    fun meEntries(game: GameType): List<ExtEntry> = call(emptyList()) { meEntries(game) }

    fun onMeEntry(game: GameType, id: String) {
        call(Unit) { onMeEntry(game, id) }
    }

    fun extPageContent(id: String): (@Composable (ScrollBehavior) -> Unit)? =
        call(null) { extPageContent(id) }
}
