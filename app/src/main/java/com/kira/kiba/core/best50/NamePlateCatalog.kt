package com.kira.kiba.core.best50

/**
 * 姓名框（名牌）素材目录 —— **由 `tools/nameplate_export.py` 自动生成，勿手改**。
 *
 * 素材来自 maimai 机台资源 `A000/AssetBundleImages/nameplate/ui_plate_*.ab`
 * （720×116，等比缩放到卡片时不得拉伸），共 96 张版本达成牌 + 1 张默认牌。
 *
 * 键 = 版本组键（[com.kira.kiba.core.stats.PlateProgress.Group.key]）+ 牌种
 * （FC/SSS/AP/FSD/MASTER，见 `PlateProgress.PlateKind.name`）。
 * 同一组合同一代号可能有多张（如「舞萌DX」同时有 熊 / 華 两套），随机取一张。
 */
object NamePlateCatalog {

    /** 默认名牌（デフォルト，浅蓝波点）：一张达成牌都没有时用它。 */
    const val DEFAULT = "np_000001"

    /** `组键|牌种|素材名` 一行一条。 */
    private const val RAW = """
OLD|AP|np_006151
OLD|FC|np_006149
OLD|FSD|np_006152
OLD|MASTER|np_006148
OLD|SSS|np_006150
双宴|AP|np_459103
双宴|AP|np_509103
双宴|FC|np_459101
双宴|FC|np_509101
双宴|FSD|np_459104
双宴|FSD|np_509104
双宴|SSS|np_459102
双宴|SSS|np_509102
宙星|AP|np_259103
宙星|AP|np_309103
宙星|FC|np_259101
宙星|FC|np_309101
宙星|FSD|np_259104
宙星|FSD|np_309104
宙星|SSS|np_259102
宙星|SSS|np_309102
暁|AP|np_006118
暁|FC|np_006116
暁|FSD|np_006119
暁|SSS|np_006117
桃|AP|np_006122
桃|FC|np_006120
桃|FSD|np_006123
桃|SSS|np_006121
橙|AP|np_006114
橙|FC|np_006112
橙|FSD|np_006115
橙|SSS|np_006113
檄|AP|np_006110
檄|FC|np_006108
檄|FSD|np_006111
檄|SSS|np_006109
櫻|AP|np_006126
櫻|FC|np_006124
櫻|FSD|np_006127
櫻|SSS|np_006125
熊華|AP|np_055103
熊華|AP|np_109103
熊華|FC|np_055101
熊華|FC|np_109101
熊華|FSD|np_055104
熊華|FSD|np_109104
熊華|SSS|np_055102
熊華|SSS|np_109102
爽煌|AP|np_159103
爽煌|AP|np_209103
爽煌|FC|np_159101
爽煌|FC|np_209101
爽煌|FSD|np_159104
爽煌|FSD|np_209104
爽煌|SSS|np_159102
爽煌|SSS|np_209102
白|AP|np_006138
白|FC|np_006136
白|FSD|np_006139
白|SSS|np_006137
真|AP|np_006102
真|FC|np_006101
真|FSD|np_006103
祭祝|AP|np_359103
祭祝|AP|np_409103
祭祝|FC|np_359101
祭祝|FC|np_409101
祭祝|FSD|np_359104
祭祝|FSD|np_409104
祭祝|SSS|np_359102
祭祝|SSS|np_409102
紫|AP|np_006130
紫|FC|np_006128
紫|FSD|np_006131
紫|SSS|np_006129
菫|AP|np_006134
菫|FC|np_006132
菫|FSD|np_006135
菫|SSS|np_006133
超|AP|np_006106
超|FC|np_006104
超|FSD|np_006107
超|SSS|np_006105
輝|AP|np_006146
輝|FC|np_006144
輝|FSD|np_006147
輝|SSS|np_006145
镜|AP|np_559103
镜|FC|np_559101
镜|FSD|np_559104
镜|SSS|np_559102
雪|AP|np_006142
雪|FC|np_006140
雪|FSD|np_006143
雪|SSS|np_006141
"""

    /** (组键 to 牌种) → 候选素材名。 */
    val table: Map<Pair<String, String>, List<String>> by lazy {
        RAW.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val p = line.split('|')
                if (p.size == 3) (p[0] to p[1]) to p[2] else null
            }
            .groupBy({ it.first }, { it.second })
    }

    /** 某版本组 + 牌种的候选素材；没有对应的版本牌返回空。 */
    fun candidates(groupKey: String, kind: String): List<String> =
        table[groupKey to kind].orEmpty()
}
