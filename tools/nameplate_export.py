# -*- coding: utf-8 -*-
"""从 Sinmai（maimai 模拟器）资源里导出姓名框素材。

产出：
  app/src/main/assets/nameplate/np_<plateId>.webp   名牌底图（720×116 原生分辨率，等比）
  app/src/main/assets/nameplate/badge_<tier>.webp   DX 评分徽章 11 档（**176×40**，对应
                                                    prefab 的 IMG_DXRating；664×130 那套是
                                                    机台大屏用的，别混）
  app/src/main/assets/nameplate/title_gold.webp     称号条（276×36，UI_CMN_Shougou_Gold）
  core/best50/NamePlateCatalog.kt                   版本代号 × 牌种 → 素材 的映射表

名牌的定义来自 `Sinmai_Data/StreamingAssets/A000/plate/<plateNNNNNN>/Plate.xml`：
  genre = 実績 且 name 为「<版本代号><牌种>」拼成的，才是版本达成牌。
  牌种：極=FC / 将=SSS / 神=AP / 舞舞=FSD（舞代号下 FSD 写作「舞舞舞」）；
  另有单独的「覇者」= MASTER（只计入舞代）。

用法：
  python tools/nameplate_export.py [模拟器根目录]
"""
import glob
import os
import sys

from PIL import Image

try:
    import UnityPy
except ImportError:
    sys.exit("需要 UnityPy：pip install UnityPy")

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)  # kiba/
ASSET_DIR = os.path.join(APP, "app", "src", "main", "assets", "nameplate")
CATALOG_KT = os.path.join(
    APP, "app", "src", "main", "java", "com", "kira", "kiba", "core", "best50",
    "NamePlateCatalog.kt",
)

DEFAULT_SIM = r"D:\SDGB_15500_20260302122354_0"

# 版本代号 → PlateProgress 的组键（同代号的多张牌随机取一张）
CODE_TO_GROUP = {
    "真": "真", "超": "超", "檄": "檄", "橙": "橙", "暁": "暁", "桃": "桃", "櫻": "櫻",
    "紫": "紫", "菫": "菫", "白": "白", "雪": "雪", "輝": "輝",
    "舞": "OLD",
    "熊": "熊華", "華": "熊華",
    "爽": "爽煌", "煌": "爽煌",
    "宙": "宙星", "星": "宙星",
    "祭": "祭祝", "祝": "祭祝",
    "双": "双宴", "宴": "双宴",
    "鏡": "镜",
}

KIND_TO_KEY = {"極": "FC", "将": "SSS", "神": "AP", "舞舞": "FSD", "覇者": "MASTER"}

CODES = list(CODE_TO_GROUP.keys())


def parse_entries(root):
    """返回 [(plateId, groupKey, kindKey, 显示名)]。"""
    base = os.path.join(root, "Sinmai_Data", "StreamingAssets", "A000", "plate")
    out = []
    for f in glob.glob(os.path.join(base, "*", "Plate.xml")):
        import xml.etree.ElementTree as ET

        try:
            r = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        if (r.findtext("genre/str") or "") != "実績":
            continue
        pid = os.path.basename(os.path.dirname(f)).replace("plate", "")
        name = r.findtext("name/str") or ""
        if name == "覇者":
            out.append((pid, "OLD", "MASTER", name))
            continue
        for code in CODES:
            if not name.startswith(code):
                continue
            rest = name[len(code):]
            # 「舞」代号的 FSD 牌名是「舞舞舞」（代号 + 牌种 舞舞）
            if rest in KIND_TO_KEY:
                out.append((pid, CODE_TO_GROUP[code], KIND_TO_KEY[rest], name))
                break
    return out


def export_texture(ab_path, dst, quality=88):
    env = UnityPy.load(ab_path)
    for o in env.objects:
        if o.type.name != "Texture2D":
            continue
        im = o.read().image.convert("RGBA")
        im.save(dst, "WEBP", quality=quality, method=5)
        return im.size
    return None


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SIM
    os.makedirs(ASSET_DIR, exist_ok=True)

    plates_ab = os.path.join(
        root, "Sinmai_Data", "StreamingAssets", "A000", "AssetBundleImages", "nameplate"
    )

    entries = parse_entries(root)
    by_group = {}
    for pid, group, kind, _name in entries:
        ab = os.path.join(plates_ab, f"ui_plate_{pid}.ab")
        if not os.path.exists(ab):
            print("  ! 缺贴图", pid)
            continue
        size = export_texture(ab, os.path.join(ASSET_DIR, f"np_{pid}.webp"))
        by_group.setdefault((group, kind), []).append(pid)
        print(f"  {pid} {group}/{kind} {size}")

    # 默认名牌（デフォルト）
    export_texture(
        os.path.join(plates_ab, "ui_plate_000001.ab"),
        os.path.join(ASSET_DIR, "np_000001.webp"),
    )

    # DX 评分徽章 11 档（白→虹，档位与 DxRatingTier 一致）。
    # prefab 里 IMG_DXRating 是 176×40，对应 `UI_CMN_DXRating_S_*` 小号，不是 664×130 那套。
    res = os.path.join(root, "Sinmai_Data", "resources.assets")
    env = UnityPy.load(res)
    want = {f"UI_CMN_DXRating_S_{i:02d}" for i in range(1, 12)} | {"UI_CMN_Shougou_Gold"}
    got = set()
    for o in env.objects:
        if o.type.name != "Texture2D":
            continue
        d = o.read()
        if d.m_Name not in want:
            continue
        if d.m_Name == "UI_CMN_Shougou_Gold":
            d.image.convert("RGBA").save(
                os.path.join(ASSET_DIR, "title_gold.webp"), "WEBP", quality=92, method=5
            )
            got.add("title")
            continue
        tier = int(d.m_Name.rsplit("_", 1)[1])
        d.image.convert("RGBA").save(
            os.path.join(ASSET_DIR, f"badge_{tier:02d}.webp"), "WEBP", quality=92, method=5
        )
        got.add(tier)
    print("徽章/称号条导出", sorted(got, key=str))

    # DX 评分数字 0..9（34×40 精灵，`NUM_Rateing` 的 SpriteCounter 用的就是这套
    # `UI_CMN_Num_26p_*`；白字贴图，游戏里用 color=#FFCB01 染成金色）。
    # 配套的 `UI_CMN_Num_26p_Outline_*` 是同尺寸、外扩约 4px 的实心剪影，
    # 游戏把它垫在数字下面染深色 —— 也就是用户看到的「细黑包边」。两张都要导。
    # 注意：**不要**拿 SEGA_NewRodinN 去排 DX 评分数字 —— 那套字体是达成率/曲名用的。
    nums = {}
    outlines = {}
    for o in env.objects:
        if o.type.name != "Sprite":
            continue
        d = o.read()
        nm = d.m_Name or ""
        if not nm.startswith("UI_CMN_Num_26p_"):
            continue
        rest = nm[len("UI_CMN_Num_26p_"):]
        is_outline = rest.startswith("Outline_")
        tail = rest[len("Outline_"):] if is_outline else rest
        if not tail.isdigit() or int(tail) > 9:
            continue
        name = f"num_out_{tail}.webp" if is_outline else f"num_{tail}.webp"
        d.image.convert("RGBA").save(
            os.path.join(ASSET_DIR, name), "WEBP", quality=92, method=5
        )
        if is_outline:
            outlines[int(tail)] = d.image.size
        else:
            nums[int(tail)] = d.image.size
    print("数字贴图导出", sorted(nums.items()), "黑边", sorted(outlines.items()))

    # ── 生成 Kotlin 映射表 ──
    lines = []
    for (group, kind) in sorted(by_group, key=lambda k: (k[0], k[1])):
        for pid in sorted(by_group[(group, kind)]):
            lines.append(f'{group}|{kind}|np_{pid}')

    body = "\n".join(lines)
    kt = f'''package com.kira.kiba.core.best50

/**
 * 姓名框（名牌）素材目录 —— **由 `tools/nameplate_export.py` 自动生成，勿手改**。
 *
 * 素材来自 maimai 机台资源 `A000/AssetBundleImages/nameplate/ui_plate_*.ab`
 * （720×116，等比缩放到卡片时不得拉伸），共 {len(lines)} 张版本达成牌 + 1 张默认牌。
 *
 * 键 = 版本组键（[com.kira.kiba.core.stats.PlateProgress.Group.key]）+ 牌种
 * （FC/SSS/AP/FSD/MASTER，见 `PlateProgress.PlateKind.name`）。
 * 同一组合同一代号可能有多张（如「舞萌DX」同时有 熊 / 華 两套），随机取一张。
 */
object NamePlateCatalog {{

    /** 默认名牌（デフォルト，浅蓝波点）：一张达成牌都没有时用它。 */
    const val DEFAULT = "np_000001"

    /** `组键|牌种|素材名` 一行一条。 */
    private const val RAW = """
{body}
"""

    /** (组键 to 牌种) → 候选素材名。 */
    val table: Map<Pair<String, String>, List<String>> by lazy {{
        RAW.lineSequence()
            .filter {{ it.isNotBlank() }}
            .mapNotNull {{ line ->
                val p = line.split('|')
                if (p.size == 3) (p[0] to p[1]) to p[2] else null
            }}
            .groupBy({{ it.first }}, {{ it.second }})
    }}

    /** 某版本组 + 牌种的候选素材；没有对应的版本牌返回空。 */
    fun candidates(groupKey: String, kind: String): List<String> =
        table[groupKey to kind].orEmpty()
}}
'''
    os.makedirs(os.path.dirname(CATALOG_KT), exist_ok=True)
    with open(CATALOG_KT, "w", encoding="utf-8", newline="\n") as f:
        f.write(kt)
    print(f"写出 {CATALOG_KT}（{len(lines)} 条）")


if __name__ == "__main__":
    main()
