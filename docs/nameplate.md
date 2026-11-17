# 姓名框（nameplate）复刻说明

Best 50 页顶部卡片与导出的 Best 50 图片表头，都用 `core/best50/NamePlateRenderer`
渲染同一张「游戏同款姓名框」。坐标**不是照着截图猜的**，是从 Sinmai 的 prefab
`UI_UserInformation`（`Sub/BackGroundCanvas` 一支）里用 UnityPy 逐节点读出来的。

## 1. 版式（名牌底图坐标系，720×116，原点左下）

| 节点 | 尺寸 | x | y(下起) | 说明 |
|---|---|---|---|---|
| `IMG_NamePlate` | 720×116 | 0 | 0 | 名牌底图（各版本图案，等比铺满） |
| `IMG_Icon` | 100×100 | 8.6 | 8.7 | 头像（账户页那套「自定义 > 抓取 > 无」） |
| `IMG_Bg` | 276×48 | 109 | 30 | 白色名字底板（圆角 9） |
| `TMP_Name` | 175×36 | 118.5 | 35 | 玩家名，左对齐，超宽缩字号 |
| `IMG_DXRating` | 176×40 | 109 | 72.7 | DX 评分徽章（11 档配色，按 Rating 取档） |
| `NUM_Rateing` | 150×34 | 128 | 75.7 | 5 位数字 |
| `IMG_Title` | 276×36 | 108 | -0.7 | 黄色称号条 |
| `UI_Title` | 255×33 | 120.5 | 3.3 | 称号条内文 |

**5 位数字的真实落位**：徽章贴图 `UI_CMN_DXRating_S_*`(176×40) 里那 5 个暗色槽位
逐像素量出来是 **x 85..161 / y 9..30**（格间还有 0x46 的分隔线），换算成名牌坐标
就是 `NUM_X=194, NUM_Y=82.7, NUM_W=76, NUM_H=21`，一格 15.2 宽。数字必须**一位一格**
居中落位，整体居中会漂。槽位是**深灰底(#5D5B5B)**，所以段位配色一律用亮色，
浅档（白/银/铂）也不能压暗。

与游戏的两点有意差异（用户确认）：
1. 不做 396 宽遮罩裁切 —— 整张 720 宽名牌都露出来；
2. 跳过 SSS5 / 真八段这类段位徽章，称号条内文改成「旧曲 / 新曲」独立 Rating。

## 2. 素材与目录

`tools/nameplate_export.py` 从模拟器资源导出，产出：

- `app/src/main/assets/nameplate/np_<plateId>.webp` —— 96 张版本达成牌 + 1 张默认牌
  (`np_000001`)，720×116 原生分辨率（**只等比缩放，不拉伸**）；
- `badge_01..11.webp` —— DX 评分徽章 11 档，**176×40**（664×130 那套是机台大屏用的，别混）；
- `title_gold.webp` —— 称号条 276×36（`UI_CMN_Shougou_Gold`）；
- `core/best50/NamePlateCatalog.kt` —— `组键|牌种|素材名` 映射表（自动生成，勿手改）。

牌种口径与 `PlateProgress` 一致：極=FC / 将=SSS / 神=AP / 舞舞=FSD，覇者=MASTER（仅舞代）。

重新导出（模拟器换版本时）：

```bash
python tools/nameplate_export.py [模拟器根目录]   # 默认 D:\SDGB_15500_20260302122354_0
```

需要 `UnityPy` 与 `Pillow`。

## 3. 选取规则

`NamePlatePicker`：用 `PlateProgress.loadOverview()` 判「某版本组某牌种 done == total」
即视为已获得，从已获得的里面按天稳定随机抽一张（种子 = 日期 + 昵称）；一张都没有
就回默认牌。结果缓存 60 秒（卡片与导出图常前后脚各调一次，避免重复扫全曲库）。

## 4. 两处消费方

- UI：`ui/me/NamePlateCard.kt` —— B50 页顶部，`aspectRatio(720/116)` + `FillWidth`，
  渲染倍率 1.6；
- 导出图：`Best50ImageBuilder.drawHeader()` —— 表头带（y=10..138）左侧按 `128/116`
  等比贴（794×128），右侧留 DX NET 标识与 OLD/NEW 合计。

## 5. 验证方式

渲染器的目视校对走 instrumented dump：临时写一个 `androidTest` 把
`NamePlateRenderer.render()` 的结果塞进相册 `Pictures/KibaTest`，`adb pull` 出来看
（验证完删掉，别留进仓库）。注意模拟器用完立刻关。
