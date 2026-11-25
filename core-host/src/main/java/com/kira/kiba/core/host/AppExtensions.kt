package com.kira.kiba.core.host

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import top.yukonga.miuix.kmp.basic.ScrollBehavior

/**
 * 插件宿主（公共契约层，纯接口无实现）。
 *
 * 设计目标：为**运行时动态加载的插件**（zip 包 + dex，见 PluginLoader）提供一组
 * 完全中性的挂接点。第三方开发者实现 [Provider] 并打包为插件即可接入。
 *
 * 铁律：
 * - 本文件与各挂接点**不得出现任何具体功能语义**——宿主只知道「有没有插件注册进来」，
 *   不知道插件会做什么；没有插件注册时所有入口返回空/否/false，应用行为与不装时完全一致。
 * - **信任分级**：官方签名的插件（trusted=true，由安装时的签名校验决定）开放全部挂接点
 *   与完整 [HostServices]；非官方插件走防范路径——数据流类挂接点（成绩源 / 刷新接管 /
 *   常规源遮蔽）一概不参与，[HostServices] 只注入只读裁剪版（发布类方法静默 no-op）。
 * - 插件通过 [Provider.onHostReady] 接收宿主服务注入；本对象**不公开**宿主服务实例，
 *   插件不得绕过注入路径获取完整服务。
 * - 注册时机：宿主在 Application.onCreate（PluginLoader 批量加载）与插件导入时调用
 *   [register]，注册过程内同步回调 [Provider.onApplicationInit] 与 [Provider.onHostReady]。
 * - 宿主内置实现（曲库检索 / 成绩发布等）由 app 模块的 `HostDefaults` 装配，本层不含实现。
 */
object AppExtensions {

    // ==================== 插件页布局契约（宿主 → 插件 单向供给） ====================

    /**
     * 宿主当前页面的顶栏遮挡量（topBar 实测高度，含塌缩联动）。
     *
     * Kiba 守则：内容满高铺开、顶栏浮在内容之上（玻璃取景需要内容延伸到顶栏下方），
     * 页面必须自己用这个值做列表 contentPadding 顶部留白——插件页照做才能「不顶进
     * topbar」。宿主在渲染插件页（[extPageContent] 的返回值）时 provide 实测值；
     * 插件页脚手架读取它，取不到（宿主未 provide / 旧宿主）时回落各自的安全值。
     *
     * 定义在 core-host 的意义：宿主与插件分属不同 classloader，但 core-host 类走
     * 双亲委派由宿主提供——这个对象是同一个实例，CompositionLocal 才能跨层传递。
     */
    val LocalExtPageTopClearance = staticCompositionLocalOf { Dp.Unspecified }

    // ==================== 数据传输对象（宿主 ↔ 插件 的中性 DTO） ====================

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

    /** 插件元信息（设置页「插件管理」列表展示用）。 */
    data class PluginInfo(
        val id: String,
        val name: String,
        val version: String,
        val summary: String,
        /** 官方签名插件 = true（由宿主安装时验签结果覆盖，插件自行填的值会被忽略）。 */
        val trusted: Boolean = false,
    )

    interface Provider {
        /** 注册完成后由宿主回调（SpUtil / DB 均已就绪）。 */
        fun onApplicationInit(app: Application) {}

        /**
         * 宿主服务注入。trusted 插件收到完整服务；非官方插件收到只读裁剪版
         * （发布类方法静默 no-op）。插件应保存该引用，之后经它调用宿主能力。
         */
        fun onHostReady(host: HostServices) {}

        /** 插件元信息（设置页「插件管理」）。返回 null 表示不参与插件管理列表。 */
        fun pluginInfo(): PluginInfo? = null

        /** 插件当前是否已启用（启用态由插件自行持久化与复验）。 */
        fun pluginEnabled(): Boolean = false

        /**
         * 用户请求启用插件。插件应**立即**执行自身验证逻辑（激活 / 环境 / 完整性）：
         * 通过则自行持久化启用态并返回 true；拒绝则返回 false（宿主保持关闭），
         * 插件可自行打开验证页面引导用户完成验证。
         * 宿主允许插件拒绝启用——关闭与否最终由插件说了算。
         */
        fun onPluginEnableRequested(context: Context): Boolean = false

        /** 用户关闭插件：插件停用自身全部能力并持久化停用态。 */
        fun onPluginDisabled(context: Context) {}

        /** 额外的成绩源选项（追加在 [ScoreSource.entries] 之后）。未授权返回空列表。 */
        fun extraScoreSources(game: GameType): List<ExtSource> = emptyList()

        /** 当前生效的额外成绩源；null 表示无（常规源生效）。 */
        fun activeExtraSource(game: GameType): ExtSource? = null

        /** 用户在源选择器里选了本插件的第 [index] 个额外源。 */
        fun onExtraSourcePicked(game: GameType, index: Int) {}

        /** 用户选回了常规源（扩展源被切出，插件应停用自身的替代模式）。 */
        fun onRegularSourcePicked(game: GameType) {}

        /**
         * 常规源 UI 是否被额外源遮蔽（为 true 时账户页隐藏常规源的登录 / 更新入口）。
         */
        fun regularSourceMasked(game: GameType): Boolean = false

        /**
         * 成绩刷新分发：返回 true 表示本游戏的成绩刷新由本插件接管（宿主跳过自身逻辑）。
         */
        fun consumeRecordsRefresh(game: GameType): Boolean = false

        /** 账户页查分器组的插件条目。未授权返回空列表。 */
        fun meEntries(game: GameType): List<ExtEntry> = emptyList()

        /** 账户页插件条目点击（广播给全部插件，由插件自行判断 id 归属）。 */
        fun onMeEntry(game: GameType, id: String) {}

        /**
         * 插件页面内容。[ExtPage(id)] 渲染时回调；返回 null（未授权 / 未知 id）时
         * 宿主渲染空占位。返回的 composable 由插件全屏自管（含顶栏返回行为）。
         */
        fun extPageContent(id: String): (@Composable (ScrollBehavior) -> Unit)? = null

        /**
         * 直连源账户页顶栏状态（舞萌成绩源为本插件的扩展源时，账户页大标题 / 副标题
         * 由此提供）。返回 null = 不覆盖默认展示（宿主昵称 / 「当前登录：xx查分器」）。
         *
         * 实现要求：**同步快照读**（组合期安全，禁止网络 IO）；动态数据由插件缓存，
         * 变化后调用 [AppExtensions.notifyDirectAccountChanged] 推送 UI 刷新。
         */
        fun directAccountState(game: GameType): ExtDirectAccount? = null

        /**
         * 宿主请求刷新直连账户状态（昵称 / 登录态）。异步执行，完成后插件应调用
         * [AppExtensions.notifyDirectAccountChanged]；失败静默（保留旧缓存值）。
         */
        fun refreshDirectAccount(game: GameType) {}

        /**
         * 请求退出直连账户（真实登出：向服务器发送登出请求并清除本机凭据）。
         * 本地已同步的查分器数据与成绩**不受影响**。
         *
         * @param onResult 完成回调（可能来自任意线程，宿主自行切主线程）：
         *   null = 成功；非 null = 错误文案
         */
        fun requestDirectLogout(game: GameType, onResult: (String?) -> Unit) {}

        /**
         * 直连源最近一次成功更新数据的时间（epoch ms；0 = 从未更新或旧版插件未提供）。
         * Best 50 页「上次更新」提示用。带默认实现——旧版插件不改此方法也能被加载
         * （跨 dex 二进制兼容铁律：接口新增方法必须带默认实现）。
         */
        fun directLastUpdate(game: GameType): Long = 0L
    }

    /**
     * 直连源账户页顶栏状态（[Provider.directAccountState] 的返回值）。
     */
    data class ExtDirectAccount(
        /** 大标题：机台昵称（未取到时为空串，宿主回落「账户」）。 */
        val title: String,
        /** 副标题：直连登录态描述。 */
        val subtitle: String,
    )

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

    // ==================== 宿主能力（应用 → 插件 单向供给） ====================

    /** 宿主向插件开放的通用能力（全部可选，未设置即不可用）。 */
    interface HostServices {
        /** 曲库按标题检索（score 查询用）。 */
        suspend fun songSearch(query: String, limit: Int = 50): List<ExtSong> = emptyList()

        /** 按 id 取歌曲；null 表示不存在。 */
        suspend fun songById(songId: Int): ExtSong? = null

        /** 按 (songId, levelIndex) 取谱面定数 / 等级标签；null 表示无此谱面。 */
        suspend fun chartInfo(songId: Int, levelIndex: Int): ExtChartInfo? = null

        /**
         * 发布外部成绩（宿主实现：走与常规源相同的落库 / B50 / 账户状态管线）。
         * 仅官方签名插件可用；非官方注入实例上此方法静默 no-op。
         * @param historySourceKey Rating 历史分桶的源 key（null 不记历史）
         */
        suspend fun publishRecords(
            records: List<ExtRecord>,
            nickname: String,
            historySourceKey: String? = null,
        ) {
        }

        /** 外部通道失败上报（宿主实现：只更新账户状态错误文案）。仅官方插件可用。 */
        fun publishError(message: String) {}

        /** 当前常规成绩源的持久化 key（扩展源切换前记录用）；null 表示不可用。 */
        fun rawScoreSourceKey(game: GameType): String? = null

        /** 宿主级轻提示。 */
        fun toast(context: Context, message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 只读裁剪版宿主服务（注入给非官方签名插件）：
     * 只读查询与提示原样透传，发布 / 写入类方法全部静默 no-op。
     */
    private class SafeHostServices(private val base: HostServices?) : HostServices {
        override suspend fun songSearch(query: String, limit: Int): List<ExtSong> =
            base?.songSearch(query, limit) ?: emptyList()

        override suspend fun songById(songId: Int): ExtSong? = base?.songById(songId)

        override suspend fun chartInfo(songId: Int, levelIndex: Int): ExtChartInfo? =
            base?.chartInfo(songId, levelIndex)

        override suspend fun publishRecords(
            records: List<ExtRecord>,
            nickname: String,
            historySourceKey: String?,
        ) {
            // 非官方插件：发布类能力禁用（静默 no-op）
        }

        override fun publishError(message: String) {
            // 非官方插件：禁用
        }

        override fun rawScoreSourceKey(game: GameType): String? = null
    }

    /** 完整宿主服务（HostDefaults 装配；仅注入给官方签名插件）。宿主装配入口 [attachHost]。 */
    @Volatile
    private var fullHost: HostServices? = null

    /** 宿主（app 模块 HostDefaults）装配完整服务；注册插件时按信任级别分发。 */
    fun attachHost(services: HostServices) {
        if (fullHost == null) fullHost = services
    }

    /** 页面导航：插件请求打开插件子页（宿主自行映射到内部路由）。 */
    @Volatile
    var pageOpener: ((id: String, title: String) -> Unit)? = null

    /** 宿主级对话框（插件在非 UI 场景请求弹窗，如刷新失败引导）。 */
    @Volatile
    var hostDialog: ((title: String, message: String) -> Unit)? = null

    // ==================== 插件注册表 ====================

    private class Entry(val provider: Provider, val id: String, val trusted: Boolean)

    @Volatile
    private var entries: List<Entry> = emptyList()

    /**
     * 注册插件（注册过程内同步回调 onApplicationInit 与 onHostReady）。
     * 同 id 已注册时先移除旧条目（覆盖安装）。
     *
     * @param trusted 官方签名插件 = true（由 PluginLoader 安装时验签决定）
     */
    fun register(p: Provider, app: Application, trusted: Boolean) {
        // 先初始化再取元信息：插件的 pluginInfo() 可能依赖 onApplicationInit
        // 注入的上下文（取反了会 NPE → id 兜底成类名，与后续按 id 路由错位）
        runCatching { p.onApplicationInit(app) }
        val id = runCatching { p.pluginInfo()?.id }.getOrNull() ?: p.javaClass.name
        entries = entries.filterNot { it.id == id } + Entry(p, id, trusted)
        val host = if (trusted) (fullHost ?: SafeHostServices(null)) else SafeHostServices(fullHost)
        runCatching { p.onHostReady(host) }
    }

    /** 注销插件（卸载时调用）。 */
    fun unregister(id: String) {
        entries = entries.filterNot { it.id == id }
    }

    private fun trustedEntries(): List<Entry> = entries.filter { it.trusted }

    // ==================== 挂接点（应用代码调用） ====================

    /** 全部已注册插件的元信息（id 以注册时记录的 Entry.id 为准；trusted 由宿主按验签结果覆盖）。 */
    fun pluginInfos(): List<PluginInfo> = entries.mapNotNull { e ->
        runCatching {
            e.provider.pluginInfo()?.copy(id = e.id, trusted = e.trusted)
        }.getOrNull()
    }

    /** 指定插件是否已启用（实时复验后的结果）。 */
    fun pluginEnabled(id: String): Boolean = byId(id)?.let { e ->
        runCatching { e.provider.pluginEnabled() }.getOrDefault(false)
    } ?: false

    /**
     * 用户请求启用指定插件；返回 false 表示插件拒绝（宿主保持关闭，
     * 插件可能已自行打开验证页面）。
     */
    fun onPluginEnableRequested(id: String, context: Context): Boolean {
        val e = byId(id)
        android.util.Log.d("KibaExt", "enable routing id=$id provider=${if (e == null) "MISSING" else e.provider.javaClass.simpleName}")
        if (e == null) return false
        return runCatching { e.provider.onPluginEnableRequested(context) }
            .onFailure { android.util.Log.w("KibaExt", "provider enable threw", it) }
            .getOrDefault(false)
    }

    /** 用户关闭指定插件。 */
    fun onPluginDisabled(id: String, context: Context) {
        byId(id)?.let { e -> runCatching { e.provider.onPluginDisabled(context) } }
    }

    /** 额外成绩源聚合（仅官方插件参与；非官方插件不进入数据流）。 */
    fun extraScoreSources(game: GameType): List<ExtSource> =
        trustedEntries().fold(emptyList()) { acc, e ->
            acc + (runCatching { e.provider.extraScoreSources(game) }.getOrDefault(emptyList()))
        }

    fun activeExtraSource(game: GameType): ExtSource? =
        trustedEntries().firstNotNullOfOrNull { e ->
            runCatching { e.provider.activeExtraSource(game) }.getOrNull()
        }

    fun onExtraSourcePicked(game: GameType, index: Int) {
        var remaining = index
        for (e in trustedEntries()) {
            val size = runCatching { e.provider.extraScoreSources(game) }.getOrDefault(emptyList()).size
            if (remaining < size) {
                runCatching { e.provider.onExtraSourcePicked(game, remaining) }
                return
            }
            remaining -= size
        }
    }

    fun onRegularSourcePicked(game: GameType) {
        trustedEntries().forEach { e ->
            runCatching { e.provider.onRegularSourcePicked(game) }
        }
    }

    fun regularSourceMasked(game: GameType): Boolean =
        trustedEntries().any { e -> runCatching { e.provider.regularSourceMasked(game) }.getOrDefault(false) }

    fun consumeRecordsRefresh(game: GameType): Boolean =
        trustedEntries().any { e ->
            runCatching { e.provider.consumeRecordsRefresh(game) }.getOrDefault(false)
        }

    /** 账户页插件条目聚合（全部插件均可参与展示类挂接点）。 */
    fun meEntries(game: GameType): List<ExtEntry> =
        entries.fold(emptyList()) { acc, e ->
            acc + (runCatching { e.provider.meEntries(game) }.getOrDefault(emptyList()))
        }

    fun onMeEntry(game: GameType, id: String) {
        entries.forEach { e -> runCatching { e.provider.onMeEntry(game, id) } }
    }

    fun extPageContent(id: String): (@Composable (ScrollBehavior) -> Unit)? {
        entries.forEach { e ->
            val content = runCatching { e.provider.extPageContent(id) }.getOrNull()
            if (content != null) return content
        }
        return null
    }

    // ==================== 直连账户顶栏（账户页 ↔ 插件） ====================

    /**
     * 直连账户状态版本号：插件在昵称 / 登录态变化后递增，账户页顶栏 collect 触发重组。
     * （ Provider 接口是同步快照读，没有 Flow 通道，用版本号做轻量重组信号。）
     */
    val directAccountVersion: kotlinx.coroutines.flow.MutableStateFlow<Int> =
        kotlinx.coroutines.flow.MutableStateFlow(0)

    /** 插件调用：直连账户状态已变化（昵称 / 登录态 / 源切换），宿主 UI 刷新。 */
    fun notifyDirectAccountChanged() {
        directAccountVersion.value = directAccountVersion.value + 1
    }

    /** 直连源账户页顶栏状态（仅官方插件参与；无插件提供时返回 null = 不覆盖默认展示）。 */
    fun directAccountState(game: GameType): ExtDirectAccount? =
        trustedEntries().firstNotNullOfOrNull { e ->
            runCatching { e.provider.directAccountState(game) }.getOrNull()
        }

    /** 请求插件刷新直连账户状态（广播给全部官方插件，插件自行判断归属并静默失败）。 */
    fun refreshDirectAccount(game: GameType) {
        trustedEntries().forEach { e ->
            runCatching { e.provider.refreshDirectAccount(game) }
        }
    }

    /**
     * 请求退出直连账户（广播语义：由提供直连源的插件处理并回调；
     * 无插件响应时不回调——UI 只在 directAccountState != null 时展示该入口）。
     */
    fun requestDirectLogout(game: GameType, onResult: (String?) -> Unit) {
        trustedEntries().forEach { e ->
            runCatching { e.provider.requestDirectLogout(game, onResult) }
        }
    }

    /** 直连源最近一次成功更新数据的时间（无插件提供时为 0）。 */
    fun directLastUpdate(game: GameType): Long =
        trustedEntries().firstNotNullOfOrNull { e ->
            runCatching { e.provider.directLastUpdate(game) }.getOrNull()
        }?.takeIf { it > 0 } ?: 0L

    private fun byId(id: String): Entry? = entries.firstOrNull { it.id == id }
}
