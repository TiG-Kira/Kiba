package com.kira.kiba.core.model

data class MaxNotesStats(
    val tap: Int,
    val hold: Int,
    val slide: Int,
    val touch: Int,
    val break_: Int,   // break 是关键字
    val total: Int
)