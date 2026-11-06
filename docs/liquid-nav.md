# 液态玻璃底部导航栏 · 实现说明

> **当前版本 = AndroidLiquidGlass 官方 demo `LiquidBottomTabs` 的 1:1 移植**
> （含 `DampedDragAnimation` / `InteractiveHighlight` 两个官方工具类的等价实现）。
> 酷安的 `LiquidGlassTabPanel`（反编译确认：`com.coolapk.market.widget.compose.liquid` 包，
> 依赖同库 backdrop + shapes）就是这套结构的工程化封装，行为逐帧对齐其录屏。

> 本文整理 Kiba 中液态玻璃底栏（`ui/material/liquid/LiquidNavigationBar.kt`）的完整实现：
> 取景结构、切换/拖动手势、液体拉伸动画、静止/动画两态切换，以及踩过的坑。
> 目标是让其它项目可以直接照抄这套结构与参数。
>
> 依赖：`io.github.kyant0:backdrop:2.0.1` + `io.github.kyant0:shapes:1.2.1`
> （[AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，Maven Central）。
> 参考效果：iOS 26 Liquid Glass tab bar / 酷安的液态底栏实验特性。

## 最终行为规格

| 状态 | 表现 |
|---|---|
| 静止 | 底栏是一条强折射玻璃（内容透过时在边缘被水滴式弯折）；选中项只有一块**淡灰色提示胶囊**（纯色，无特效）+ 图标变色/微放大。没有浮起玻璃。 |
| 点击切换 | 灰色提示的位置上浮起一块**液态玻璃**，从旧 tab 流到新 tab；移动中速度越快被拉得越长（前缘钉住、后缘拖尾）；到位后玻璃淡出，**融回灰色提示**。 |
| 拖动 | 横向拖动时玻璃胶囊 `snapTo` 直接跟手（不播动画），松手吸附到最近的 tab（换页走回调，没换页就弹回去）。 |
| 图标高亮 | 跟随胶囊的**实际位置**（`round(animatedIndex.value)`），不跟随目标 tab。 |

关键观感（也是最容易做错的地方）：胶囊移动时**底栏自己的图标会被吸进折射里变形**——
看起来像液体拖着底栏内容走，而不是一块色块在图标上滑过。

## 取景结构（核心）—— demo 的四层绘制

酷安/demo 的结构比"底栏 + 胶囊"多一层**隐形导出层**，这是效果的关键：

```
pageBackdrop     页面内容的取景层（主屏已有）
tabsBackdrop     底栏自身的取景层（由隐形 Row B 录制，内容被染成强调色）
combined         rememberCombinedBackdrop(pageBackdrop, tabsBackdrop) —— 胶囊采样两层
```

组件内的绘制顺序（z 序从下到上）：

```kotlin
// Row A：可见底栏（vibrancy + blur(8) + lens(24,24)），表面 = 容器色
//   按压时整条底栏 scale = 1 + 16dp/宽（layerBlock 里随 pressProgress）
Row(
    Modifier.graphicsLayer { translationX = panelOffset }
        .drawBackdrop(pageBackdrop, Capsule,
            effects = { vibrancy(); blur(8.dp.toPx()); lens(24.dp.toPx(), 24.dp.toPx()) },
            layerBlock = { val p = damped.pressProgress
                           val s = lerp(1f, 1f + 16f.dp.toPx() / size.width, p)
                           scaleX = s; scaleY = s },
            onDrawSurface = { drawRect(containerColor) })
        .then(interactiveHighlight.modifier)      // 镜面光斑
        .height(54.dp).fillMaxWidth().padding(4.dp),
) { /* 图标 + 标签，点击回调挂这里 */ }

// Row B：隐形导出层 —— alpha(0) 不可见，只负责录制
Row(
    Modifier.clearAndSetSemantics {}
        .alpha(0f)
        .layerBackdrop(tabsBackdrop)              // ← 录制
        .graphicsLayer { translationX = panelOffset }
        .drawBackdrop(pageBackdrop, Capsule,
            effects = { val p = damped.pressProgress
                        vibrancy(); blur(8.dp.toPx())
                        lens(24.dp.toPx() * p, 24.dp.toPx() * p) },
            highlight = { Highlight.Default.copy(alpha = damped.pressProgress) })
        .height(46.dp).fillMaxWidth().padding(horizontal = 4.dp),
) { /* 同样的图标，但 tint = 强调色；**不挂 clickable**（见踩坑 8） */ }

// 胶囊：采样 combined，一切玻璃效果 × pressProgress
Box(
    Modifier.padding(horizontal = 4.dp)
        .graphicsLayer { translationX = damped.value * tabWidth + panelOffset }
        .then(interactiveHighlight.gestureModifier)
        .then(damped.modifier)
        .drawBackdrop(
            backdrop = rememberCombinedBackdrop(pageBackdrop, tabsBackdrop),
            shape = { Capsule() },
            effects = { val p = damped.pressProgress
                        lens(10.dp.toPx() * p, 14.dp.toPx() * p, chromaticAberration = true) },
            highlight = { Highlight.Default.copy(alpha = damped.pressProgress) },
            shadow = { Shadow(alpha = damped.pressProgress) },
            innerShadow = { InnerShadow(8.dp * damped.pressProgress, damped.pressProgress) },
            layerBlock = {
                scaleX = damped.scaleX; scaleY = damped.scaleY
                val v = damped.velocity / 10f
                scaleX /= 1f - (v * 0.75f).coerceIn(-0.2f, 0.2f)   // 液体拉伸（除法对称式）
                scaleY *= 1f - (v * 0.25f).coerceIn(-0.2f, 0.2f)
            },
            onDrawSurface = {
                val p = damped.pressProgress
                // 静止（p=0）：Black 10% = 灰色提示底；动画中趋近透明让折射说话
                drawRect(if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                         alpha = 1f - p)
                drawRect(Color.Black.copy(alpha = 0.03f * p))
            })
        .height(46.dp).fillMaxWidth(1f / tabCount),
)
```

**要点**：
- **Row B 是"液体拖动图标"的来源**：胶囊折射吸进的底栏图标是这层染过色的录制画面；
  静止时 pressProgress=0 → Row B 的 lens 强度为 0，只有图标本身被录进 tabsBackdrop。
- **Row B 不要画表面色、不要整层 ColorFilter.tint**：整层 tint 会把玻璃表面一起染成
  实心强调色，胶囊折射出来就是一整块色块（AVD 实证）；染色只作用于图标本身。
- **Row B 不挂 clickable**：它 alpha(0) 盖在整个底栏上，挂了空点击会把真实点按全拦掉
  （AVD 实证：点了没反应）。
- **表面色必须淡**：玻璃和灰底都压在图标路径上，色重了 tag 消失。

## 动画：DampedDragAnimation（官方工具类的等价实现）

demo 的胶囊动画不直接用 `animateFloatAsState`，而是 `catalog/utils/DampedDragAnimation.kt`
（demo 引用它，酷安的 `internal/DragGestureInspector` 是同类角色）。它是一个胶囊动画状态机：

```kotlin
class DampedDragAnimation(
    animationScope: CoroutineScope,
    initialValue: Float,
    val valueRange: ClosedFloatingPointRange<Float>,   // 0f..(tabCount-1)f
    visibilityThreshold: Float,                        // 0.001f
    initialScale: Float,                               // 1f
    pressedScale: Float,                               // 78f/56f ≈ 1.39（按住时鼓起）
    val onDragStarted: () -> Unit,
    val onDragStopped: () -> Unit,                     // 松手：吸附 round(targetValue) + 换页
    val onDrag: (position: Offset, dragAmount: Offset) -> Unit,
) {
    val value: Float            // 胶囊位置（tab 下标，可小数）
    val targetValue: Float
    val velocity: Float         // 拉伸公式用
    val pressProgress: Float    // 按压 0..1，玻璃特效全部乘它
    val scaleX / scaleY         // lerp(1f, 1.39f, pressProgress)
    val modifier                // detectDragGestures：拖动跟手
    fun updateValue(t: Float)                       // 拖动 snapTo
    suspend fun animateToValue(t: Float)            // press→1 → 位置动画 → press→0
}
```

要点：
- **位置弹簧是临界阻尼**（dampingRatio = 1，无过冲）——"液体"的弹性来自拉伸与
  按压缩放，位置本身不弹（弹了就像滑块而不是液体）；
- **点击/程序化切换也要把 pressProgress 拉起**：`animateToValue` 先 press→1（玻璃浮现）
  再位置到位再 press→0（凝回灰提示）。demo 原版只有拖动有特效，酷安点击也有——
  这是对齐酷安行为的补充；
- 拉伸公式在胶囊的 `layerBlock` 里（见上），除法对称式：
  `scaleX /= 1 - (velocity/10 * 0.75).coerceIn(-0.2f, 0.2f)`。

InteractiveHighlight（同样是官方工具类）：底栏表面跟随胶囊位置的镜面光斑，
`modifier` 挂底栏（drawWithContent 画径向白光），`gestureModifier` 挂胶囊（按压驱动）。

## 手势：点按 + 拖动（都在 DampedDragAnimation / 状态流里）

- **点按**：TabContent 的 `clickable` → `onSelect(i)` → 外部 state 变化 →
  `snapshotFlow { selectedIndex }` → `currentIndex = it` →
  `snapshotFlow { currentIndex }.drop(1).collectLatest { animateToValue(it); onSelect(it) }`；
- **拖动**：`damped.modifier` 的 `detectDragGestures`，`onDrag` 里
  `updateValue(target + dragAmount.x / tabWidth)` 跟手 + `offsetAnimation.snapTo(+=dx)`
  （整条底栏让位）；`onDragStopped` 里 `setCurrent(round(targetValue))` 走同一条流。

两条路径最终都汇入 `currentIndex` 流，动画与 `onSelect` 上报只发生在一处。

## 两态切换：灰色提示 ↔ 液态玻璃

胶囊是**常驻**的（不做显隐），但分成两个独立图层、z 序不同：

```kotlin
// 灰色提示底（z 序在图标下）：常驻，纯色，无特效
Box(Modifier.graphicsLayer { translationX = … }
          .width(tabWidth).padding(4.dp)
          .clip(Capsule()).background(grey, Capsule()))

// …… 底栏 + 图标 ……

// 液态玻璃（z 序在图标上）：只在动画中
if (indicatorAlpha > 0.01f) {
    Box(Modifier.graphicsLayer {
            translationX = …; scaleX = 1f + stretch
            alpha = indicatorAlpha          // 淡出 = 液滴落回、凝回灰色提示
        }
        .width(tabWidth).padding(4.dp)
        .drawBackdrop(combined, …))
}
```

玻璃淡出的过程就是"液滴落回选中位、图标从玻璃下浮现回灰底上"的过渡，
不需要额外的消失动画。两层各自持有 `graphicsLayer`（灰底不做拉伸，拉伸只属于在飞的玻璃）。

```kotlin
val indicatorAlpha by animateFloatAsState(
    if (indicatorVisible) 1f else 0f,
    tween(durationMillis = 140),
)
```

## 参数速查

| 参数 | 值 | 说明 |
|---|---|---|
| 底栏高度 | 54dp | 与 miuix `GlassNavigationBar` 对齐，切换材质零跳动 |
| 底栏 lens | 16dp / 20dp + 色散 | demo `LiquidBottomTabs` 口径 |
| 底栏表面 | 亮色 White 60% / 暗色 Black 40% | 玻璃的底色，折射才是主角 |
| 胶囊 lens | 24dp / 28dp + 色散 | 比底栏狠，才能吸进底栏内容 |
| 胶囊表面 | 亮色 White 38% / 暗色 White 10% | **必须淡**：胶囊压在图标上，色重了 tag 被盖没 |
| 静止提示 | 亮色 Black 4.5% / 暗色 White 6% | 酷安同款淡灰，图标画在它上面 |
| spring | dampingRatio 0.7 / StiffnessLow | 轻微过冲回弹 |
| 拉伸系数 | `|v| * 0.06`，clamp 0.22 | 常见切换拉到 15%~22% |
| 图标 | 28dp（对齐 `GlassNavigationBarDefaults.IconSize`） | |
| 标签 | 11sp / lineHeight 12sp | |

## 踩坑记录

1. **`Animatable` 不要放在 `BoxWithConstraints` 里面**。
   `BoxWithConstraints` 是 SubcomposeLayout，content 会在重新测量/子组合时重跑，
   里面的 `remember` 有被重建的风险——一旦重建，`Animatable` 以新 index 初始化，
   切换就变成**瞬移**，拉伸动画根本不会播（AVD 逐帧录屏才发现）。
   动画状态一律放在组件最外层。

2. **`layerBackdrop` 必须在 `drawBackdrop` 之前**。
   顺序反了录到的是"玻璃之下"的空内容，胶囊折射不到底栏图标。

3. **`drawBackdrop` 的所有 lambda 必须 remember**。
   身份变化 → `invalidateDrawCache` → 重录取景 → 滚动掉帧。

4. **静止态的灰底 `effects = {}`**（空实现）。
   提示底是纯色，任何折射/模糊都会让它看起来像第二块玻璃。

5. **图标高亮跟随胶囊位置而不是目标 tab**。
   否则点了第 5 个 tab，图标瞬间全变、玻璃还在半路，观感割裂。
   用 `derivedStateOf { animatedIndex.value.roundToInt() }` 只在跨 tab 边界时重组。

6. **z 序分三层，不能把灰底和玻璃塞进同一个 Box**。
   灰底必须在图标下面（它是提示，图标要清晰），玻璃必须在图标上面（盖住才有折射扭曲）。
   最初版本把两层叠在同一个常驻胶囊里压在图标上方，结果静止态的 tag 被盖没。
   另外玻璃表面色一开始给了 65% 白，图标直接"消失"——降到 38% 才透得出来。

8. **隐形导出层（Row B）不要挂 clickable、不要画表面色、不要整层 tint**。
   它 alpha(0) 盖在整个底栏上：挂空点击 = 真实点按全被拦；画表面色/整层 tint =
   tabsBackdrop 变成实心强调色，胶囊折射成一整块色块（三个都是 AVD 实证踩过的）。

7. **验证手段**：动画中间态截图抓不到，用
   `adb shell screenrecord --size 480x1068 --time-limit 3` 录屏 +
   `ffmpeg -i nav.mp4 -vf fps=60 f_%03d.png` 抽帧 + PIL 帧差定位切换区间，
   再逐帧检查胶囊宽度/位置。
