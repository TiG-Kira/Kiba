package com.kira.kiba.core.utils

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import java.io.File

/**
 * 设备性能档位。真·高级材质按档位决定是否启用、以及按哪一档渲染开销运行。
 */
enum class DeviceTier {
    /** 评分不足：不建议使用高级材质（渲染链路为 blur + 多次着色器采样）。 */
    LOW,

    /** 达标：启用标准档（适度降采样）。 */
    MID,

    /** 宽裕：启用完整档（最小降采样、开启色散）。 */
    HIGH,

    ;

    companion object {
        fun of(score: Int): DeviceTier = when {
            score >= 75 -> HIGH
            score >= DeviceGrade.PASS_SCORE -> MID
            else -> LOW
        }
    }
}

/**
 * 一次评分的结果快照。
 *
 * @property total 总分 0..100
 * @property tier 由 [total] 推得的档位
 * @property details 逐项明细，用于在设置页解释分数构成
 */
data class DeviceScore(
    val total: Int,
    val tier: DeviceTier,
    val ramGb: Float,
    val cores: Int,
    val maxFreqMHz: Int,
    val refreshHz: Float,
    val glEsVersion: Int,
    val lowRamDevice: Boolean,
    val sdkInt: Int,
    val details: List<Pair<String, Int>>,
)

/**
 * 设备性能评分。
 *
 * 评分只依赖 [Build] 静态信息与只读系统服务，不做耗时 benchmark，因此可以在主线程调用、
 * 也可以在设置页被用户手动「重新评分」即时重算。
 *
 * 权重分配（合计 100）：内存 30 / CPU 核数 20 / CPU 主频 15 / 刷新率 15 / GL ES 10 / 系统版本 10。
 */
object DeviceGrade {

    /** 达标分数线：达到此分才允许开启真·高级材质。 */
    const val PASS_SCORE = 55

    private const val W_RAM = 30
    private const val W_CORES = 20
    private const val W_FREQ = 15
    private const val W_REFRESH = 15
    private const val W_GLES = 10
    private const val W_SDK = 10

    /** 最新的评分结果（进程内缓存，避免每次重算）。 */
    @Volatile
    var current: DeviceScore? = null
        private set

    /** 评测一份 [DeviceScore] 并写入 [current]。 */
    @Synchronized
    fun evaluate(context: Context): DeviceScore {
        val app = context.applicationContext
        val score = runCatching { compute(app) }.getOrElse { fallback() }
        current = score
        return score
    }

    /** 读取缓存，未评测过则现算。 */
    fun cachedOrEvaluate(context: Context): DeviceScore =
        current ?: evaluate(context)

    /** 当前是否允许开启真·高级材质。 */
    fun passes(context: Context): Boolean = cachedOrEvaluate(context).total >= PASS_SCORE

    private fun fallback(): DeviceScore = DeviceScore(
        total = 0,
        tier = DeviceTier.LOW,
        ramGb = 0f,
        cores = 0,
        maxFreqMHz = 0,
        refreshHz = 60f,
        glEsVersion = 0,
        lowRamDevice = true,
        sdkInt = Build.VERSION.SDK_INT,
        details = listOf("评测失败" to 0),
    )

    private fun compute(context: Context): DeviceScore {
        val am = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memoryInfo)
        val totalMem = memoryInfo.totalMem
        val lowRam = am?.isLowRamDevice == true

        val ramGb = if (totalMem > 0L) totalMem / (1024f * 1024f * 1024f) else 0f
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val maxFreqMHz = readMaxCpuFreqKhz(cores) / 1000
        val refreshHz = readRefreshRate(context)
        val glEsVersion = am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        val sdkInt = Build.VERSION.SDK_INT

        // 内存：12G+ 满分；低端机判定直接压到最低档（这类设备 malloc 与 GPU 内存都吃紧）
        val ramScore = when {
            lowRam -> (W_RAM * 0.2f).toInt()
            ramGb >= 12f -> W_RAM
            ramGb >= 8f -> 24
            ramGb >= 6f -> 18
            ramGb >= 4f -> 12
            ramGb > 0f -> 6
            else -> 0
        }.coerceAtMost(W_RAM)

        val coreScore = when {
            cores >= 8 -> W_CORES
            cores >= 6 -> 15
            cores >= 4 -> 11
            else -> 5
        }.coerceAtMost(W_CORES)

        val freqScore = when {
            maxFreqMHz >= 3000 -> W_FREQ
            maxFreqMHz >= 2500 -> 12
            maxFreqMHz >= 2000 -> 8
            maxFreqMHz > 0 -> 4
            else -> 0
        }.coerceAtMost(W_FREQ)

        val refreshScore = when {
            refreshHz >= 120f -> W_REFRESH
            refreshHz >= 90f -> 12
            refreshHz >= 60f -> 9
            refreshHz > 0f -> 5
            else -> 0
        }.coerceAtMost(W_REFRESH)

        val glesScore = when {
            glEsVersion >= 0x00030002 -> W_GLES
            glEsVersion >= 0x00030001 -> 8
            glEsVersion >= 0x00030000 -> 6
            glEsVersion > 0 -> 3
            else -> 0
        }.coerceAtMost(W_GLES)

        val sdkScore = when {
            sdkInt >= 35 -> W_SDK
            sdkInt >= 33 -> 8
            else -> 5
        }.coerceAtMost(W_SDK)

        val details = listOf(
            "内存 ${"%.1f".format(ramGb)}GB" to ramScore,
            "CPU ${cores}核" to coreScore,
            "主频 ${maxFreqMHz / 1000f}GHz" to freqScore,
            "刷新率 ${refreshHz.toInt()}Hz" to refreshScore,
            "GL ES ${glEsMajor(glEsVersion)}" to glesScore,
            "Android $sdkInt" to sdkScore,
        )

        val total = (ramScore + coreScore + freqScore + refreshScore + glesScore + sdkScore)
            .coerceIn(0, 100)

        return DeviceScore(
            total = total,
            tier = DeviceTier.of(total),
            ramGb = ramGb,
            cores = cores,
            maxFreqMHz = maxFreqMHz,
            refreshHz = refreshHz,
            glEsVersion = glEsVersion,
            lowRamDevice = lowRam,
            sdkInt = sdkInt,
            details = details,
        )
    }

    private fun glEsMajor(version: Int): String {
        val major = (version and 0xffff0000.toInt()) ushr 16
        val minor = version and 0x0000ffff
        return "$major.$minor"
    }

    /**
     * 读取最大主频（kHz）。cpuinfo_max_freq 在部分内核上是 selinux 受限的，读不到就逐个 CPU 试，
     * 全都失败时返回 0（该项得 0 分，不崩）。
     */
    private fun readMaxCpuFreqKhz(cores: Int): Int {
        for (index in 0 until cores.coerceAtMost(16)) {
            val value = runCatching {
                File("/sys/devices/system/cpu/cpu$index/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toIntOrNull()
            }.getOrNull()
            if (value != null && value > 0) return value
        }
        // 部分设备只在 policy 节点暴露上限
        return runCatching {
            File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq")
                .readText().trim().toIntOrNull()
        }.getOrNull() ?: 0
    }

    /** 主屏刷新率，取不到时回退 60Hz。 */
    private fun readRefreshRate(context: Context): Float {
        return runCatching {
            val dm = context.getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            display?.refreshRate?.takeIf { it > 0f }
        }.getOrNull() ?: 60f
    }
}
