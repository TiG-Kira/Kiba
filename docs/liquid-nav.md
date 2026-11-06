# 液态玻璃底部导航栏 · 实现说明

> **当前实现 = AndroidLiquidGlass 官方 demo `LiquidBottomTabs` 的 1:1 移植**
> （含 `DampedDragAnimation` / `InteractiveHighlight` 两个官方工具类的等价实现）。
> 酷安的 `LiquidGlassTabPanel`（反编译确认：`com.coolapk.market.widget.compose.liquid` 包，
> 依赖同库 backdrop + shapes）就是这套结构的工程化封装，行为逐帧对齐其录屏。

> 本文整理 Kiba 中液态玻璃底栏（`ui/material/liquid/LiquidNavigationBar.kt`）的完整实现：
> 取景结构、切换/拖动手势、液体拉伸动画、静止/动画两态切换，以及踩过的坑。
> 目标是让其它项目可以直接照抄这套结构与参数。
>
> 本文只描述 **`MaterialFamily.LIQUID`** 这一条分支。默认的柔光玻璃+ / 未开启高级材质时的
> 经典底栏都由 miuix `GlassNavigationBar` 承担，与本套实现无关，改动液态底栏时不要碰它们。
>
> 依赖：`io.github.kyant0:backdrop:2.0.1` + `io.github.kyant0:shapes:1.2.1`
> （[AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，Maven Central）。
> 参考效果：iOS 26 Liquid Glass tab bar / 酷安的液态底栏实验特性。

## 最终行为规格

| 状态 | 表现 |
|---|---|
| 静止 | 底栏是一条强折射玻璃（内容透过时在边缘被水滴式弯折）；选中项只有一块**淡灰色提示胶囊**（纯色，无特效）+ 图标变色/微放大。没有浮起玻璃。 |
| 点击切换 | 灰色提示的位置上浮起一块**液态玻璃**，从旧 tab 流到新 tab；移动中速度越快被拉得越长（前缘钉住、后缘拖尾）；到位后玻璃淡出，**融回灰色提示**。 |
| 拖动 | 横向拖动时玻璃胶囊 `snapTo` 直接跟手（不播动画），松手吸附到最近的 tab（换页走回调，没换页就弹回去），整条底栏的让位同步回弹。 |
| 图标高亮 | 跟随胶囊的**实际位置**（`round(animatedIndex.value)`），不跟随目标 tab。 |

关键观感（也是最容易做错的地方）：胶囊移动时**底栏自己的图标会被吸进折射里变形**——
看起来像液体拖着底栏内容走，而不是一块色块在图标上滑过。

## 取景结构（核心）—— demo 的四层绘制

酷安/demo 的结构比"底栏 + 胶囊"多一层**隐形导出层**，这是效果的关键：

```
pageBackdrop     页面内容的取景层（主屏已有）
tabsBackdrop     底栏自身的取景层（由隐形 Row B 录制，图标被染成强调色）
combined         rememberCombinedBackdrop(pageBackdrop, tabsBackdrop) —— 胶囊采样两层
```

组件内的绘制顺序（z 序从下到上）：

```kotlin
// 所有 drawBackdrop lambda 一律在最外层 remember（见踩坑 3）
val capsuleShape: () -> Shape = remember { { Capsule() } }
val barEffects: BackdropEffectScope.() -> Unit = remember { { vibrancy(); blur(8.dp); lens(24.dp, 24.dp) } }
val barLayerBlock: GraphicsLayerScope.() -> Unit = remember(damped) { /* scale × pressProgress */ }
val barSurface: DrawScope.() -> Unit = remember(containerColor) { { drawRect(containerColor) } }
// … exportEffects / exportHighlight / pill* 同理 …

// Row A：可见底栏（vibrancy + blur(8) + lens(24,24)），表面 = 容器色
//   按压时整条底栏 scale = 1 + 16dp/宽（layerBlock 里随 pressProgress）
Row(
    Modifier.graphicsLayer { translationX = panelOffset }
        .drawBackdrop(pageBackdrop, capsuleShape,
            effects = barEffects,
            layerBlock = barLayerBlock,
            onDrawSurface = barSurface)
        .then(interactiveHighlight.modifier)      // 镜面光斑
        .height(54.dp).fillMaxWidth().padding(4.dp),
) { /* 图标 + 标签，onClick 只写 NavSettle，不直接 onSelect */ }

// Row B：隐形导出层 —— alpha(0) 不可见，只负责录制
Row(
    Modifier.clearAndSetSemantics {}
        .alpha(0f)
        .layerBackdrop(tabsBackdrop)              // ← 录制，必须在 drawBackdrop 之前
        .graphicsLayer { translationX = panelOffset }
        .drawBackdrop(pageBackdrop, capsuleShape,
            effects = exportEffects,             // lens(24*p, 24*p)
            highlight = exportHighlight)          // Highlight.Default × p
        .then(interactiveHighlight.modifier)
        .height(46.dp).fillMaxWidth().padding(horizontal = 4.dp),
) { /* 同样的图标，tint = 强调色，scale = lerp(1, 1.2, p)；**不挂 clickable** */ }

// 胶囊：采样 combined，一切玻璃效果 × pressProgress
Box(
    Modifier.padding(horizontal = 4.dp)
        .graphicsLayer {
            translationX = if (isLtr) damped.value * tabWidth + panelOffset
                           else size.width - (damped.value + 1f) * tabWidth + panelOffset
        }
        .then(interactiveHighlight.gestureModifier)
        .then(damped.modifier)
        .drawBackdrop(
            backdrop = combined,
            shape = capsuleShape,
            effects = pillLens,                   // lens(10*p, 14*p, 色散)
            highlight = pillHighlight, shadow = pillShadow, innerShadow = pillInnerShadow,
            layerBlock = pillLayerBlock,          // 按压缩放 + 速度拉伸
            onDrawSurface = pillSurface)          // 灰提示(1-p) + 淡表面(3%×p)
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
- **Row B 的图标按压缩放**（`lerp(1f, 1.2f, p)`，demo 的 `LocalLiquidBottomTabScale`
  口径）：折射里的图标在按住时一起鼓起，静止时 p=0 完全无变化。

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
    val onDragStopped: () -> Unit,                     // 松手：吸附 round(targetValue)
    val onDrag: (position: Offset, dragAmount: Offset) -> Unit,
) {
    val value: Float            // 胶囊位置（tab 下标，可小数）
    val targetValue: Float
    val velocity: Float         // 拉伸公式用
    val pressProgress: Float    // 按压 0..1，玻璃特效全部乘它
    val scaleX / scaleY         // lerp(1f, 1.39f, pressProgress)
    val modifier                // detectDragGestures：按下鼓起、拖动跟手
    fun updateValue(t: Float)                       // 拖动 snapTo
    suspend fun animateToValue(t: Float)            // press→1 → 位置动画 → press→0
}
```

要点：
- **位置弹簧是临界阻尼**（dampingRatio = 1，无过冲）——"液体"的弹性来自拉伸与
  按压缩放，位置本身不弹（弹了就像滑块而不是液体）；
- **点击/程序化切换/拖动松手一律走 `animateToValue`**：先 press→1（玻璃浮现）
  再位置到位再 press→0（凝回灰提示）。demo 原版只有拖动有特效，酷安点击也有——
  这是对齐酷安行为的补充。位置已经在目标时（`abs < threshold`）也要把 press 落回 0，
  否则"松手正好停在整数位"会让玻璃一直亮着；
- **press 的起落只由 `animateToValue` 收尾**：手势 `onDragEnd/onDragCancel` 不再单独
  launch press 动画，两个协程抢同一个 `Animatable` 会让玻璃停在半亮或吸附动画被取消；
- 拉伸公式在胶囊的 `layerBlock` 里（见上），除法对称式：
  `scaleX /= 1 - (velocity/10 * 0.75).coerceIn(-0.2f, 0.2f)`。

InteractiveHighlight（同样是官方工具类）：底栏表面跟随胶囊位置的镜面光斑，
`modifier` 挂 Row A / Row B（drawWithContent 画径向白光），`gestureModifier` 挂胶囊（按压驱动）。
光斑中心读的是 `panelOffsetState.value`（State），不是捕获的 Float，拖到哪亮到哪。

## 手势：点按 + 拖动 + 外部切 Tab（汇入同一条状态流）

```kotlin
private data class NavSettle(val index: Int, val serial: Int)

var navSettle by remember { mutableStateOf(NavSettle(selectedIndex, 0)) }
var reportedIndex by remember { mutableIntStateOf(selectedIndex) }

// setCurrent / onClick 都写成单次状态写（serial 自增保证 index 不变也 emit）
fun setCurrent(index: Int) { navSettle = NavSettle(index, navSettle.serial + 1) }

LaunchedEffect(selectedIndex) {                          // 外部切 Tab
    if (selectedIndex != navSettle.index) setCurrent(selectedIndex)
}
LaunchedEffect(damped) {
    snapshotFlow { navSettle }.drop(1).collectLatest { (index, _) ->
        damped.animateToValue(index.toFloat())           // 动画 + press 起落只此一处
        if (index != reportedIndex) {                    // 换页回调只此一处
            reportedIndex = index
            onSelect(index)
        }
    }
}
```

- **点按**：TabContent 的 `clickable` 只写 `NavSettle(i, serial+1)`，**不直接调 `onSelect`**。
  否则一次点按会上报两次，而且页面在液滴还没飞过去时就切了（拖动路径却是动画后才切），
  两条路径时机不一致；
- **拖动**：`damped.modifier` 的 `detectDragGestures`，`onDrag` 里
  `updateValue(target + dragAmount.x / tabWidth * (isLtr ? 1 : -1))` 跟手 +
  `offsetAnimation.snapTo(+= dx)`（整条底栏让位）；`onDragStopped` 里
  `setCurrent(round(targetValue))` +
  `offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))` 回弹；
- **`NavSettle.serial` 是"没换页"的兜底**：松手吸附回原 tab 时 index 不变，
  没有自增序号流就不会触发，胶囊会卡在两 tab 之间（拖动没换页 = 应该弹回去）。
  它必须是**同一个状态对象里的一个字段**、一次写一次 emit；拆成
  `setCurrent()` + `settleSerial++` 两次写会产生两次 emit，`collectLatest`
  取消前一个协程时连那次的 `onSelect` 一起吞掉；
- **RTL**：拖动增量取反、胶囊 `translationX` 用 `size.width - (value+1)*tabWidth`、
  光斑中心同样镜像，三者都要按 `LocalLayoutDirection` 翻，缺一个就会左右打架。

三条路径最终都汇入同一条流，动画与 `onSelect` 上报各只发生一次。

**按下立刻浮现**（对齐酷安：玻璃从当前 tag 诞生）：底栏本体另挂一个
`awaitEachGesture { awaitFirstDown → pressDown(); waitForUpOrCancellation → pressUpDelayed() }`——
down 时刻 `press.animateTo(1f, StiffnessHigh)` 立即让玻璃在当前选中 tab 浮现，
不等抬起后的状态流。抬手后 280ms 兜底回落；若触发了换页，`animateToValue` 的
`press.animateTo(1f)` 会抢占回落（同一 Animatable 并发互斥），玻璃保持到飞到位再淡出。

## 两态切换：灰色提示 ↔ 液态玻璃（同一块胶囊）

胶囊是**常驻**的，而且**不做显隐、不拆成两个图层**——灰色提示与液态玻璃是
同一块 `drawBackdrop` 胶囊在不同 `pressProgress` 下的两种画法：

```kotlin
onDrawSurface = {
    val p = damped.pressProgress
    // 静止（p=0）：Black 10% = 灰色提示底；动画中趋近透明让折射说话
    drawRect(if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
             alpha = 1f - p)
    drawRect(Color.Black.copy(alpha = 0.03f * p))
}
```

`effects / highlight / shadow / innerShadow / layerBlock` 全部乘 `p`：
p→1 时玻璃浮现、被速度拉长、吸进 tabsBackdrop 的染色图标；p→0 时
折射/高光/阴影一起淡出，表面色渐变回灰提示 —— 这就是"液滴落回选中位、
图标从玻璃下浮现回灰底上"的过渡，不需要额外的 `indicatorAlpha` 消失动画。

> 早期版本把「灰底（图标下）+ 玻璃层（图标上）」拆成两层并用
> `animateFloatAsState(tween(140))` 淡出玻璃。那套结构对应的是只采 `pageBackdrop`
> 的实现；改成 demo 的 `combined(page, tabs)` 后，胶囊必须常驻在图标**之上**才能
> 把底栏图标吸进折射，拆层反而会破坏取景关系。现在靠「淡表面色 + 特效 × p」达成
> 同样的两态观感，且录景层数更少。

## 参数速查

| 参数 | 值 | 说明 |
|---|---|---|
| 底栏高度 | 54dp（Row B / 胶囊 46dp） | 与 miuix `GlassNavigationBar` 对齐，切换材质零跳动 |
| Row A effects | `vibrancy + blur(8dp) + lens(24dp, 24dp)` | demo `LiquidBottomTabs` 口径 |
| Row A 表面 | 亮 `FAFAFA` 40% / 暗 `121212` 40% | 玻璃的底色，折射才是主角 |
| Row A 按压 | `scale = 1 + 16dp / size.width` × p | 按住时整条底栏微鼓 |
| Row B effects | `vibrancy + blur(8dp) + lens(24dp·p, 24dp·p)` | p=0 时只剩图标被录进 tabsBackdrop |
| Row B 图标 | tint = `0088FF` / `0091FF`，scale `lerp(1, 1.2, p)` | 折射里染过色、按住鼓起的图标 |
| 胶囊 lens | `lens(10dp·p, 14dp·p, chromaticAberration = true)` | 色散 + 采样 combined，才吸得进底栏内容 |
| 胶囊表面 | 亮 Black 10% ×(1-p) / 暗 White 10% ×(1-p)，再叠 Black 3%×p | **必须淡**：胶囊压在图标上，色重了 tag 被盖没 |
| 静止提示 | 上一条 p=0 的结果 | 酷安同款淡灰，图标画在它上面 |
| 按压缩放 | 胶囊 `78/56 ≈ 1.39`；Row B 图标 `1 → 1.2` | demo 口径 |
| 位置 spring | `dampingRatio = 1` / `StiffnessMediumLow` | 临界阻尼，无过冲 |
| press spring | `dampingRatio = 1` / `StiffnessMediumLow` | 玻璃浮现/凝回 |
| 松手回弹 spring | `spring(1f, 300f, 0.5f)` | 整条底栏让位归零 |
| 拉伸 | `scaleX /= 1 - (v/10·0.75).clamp(±0.2)`；`scaleY *= 1 - (v/10·0.25).clamp(±0.2)` | 除法对称式，速度越快越长 |
| 让位 | `4dp × sign(f) × EaseOut(|f|)`，`f = offset / 底栏宽` | 拖动时整条底栏微移 |
| 图标 / 标签 | 28dp / 11sp，lineHeight 12sp，间距 1dp | 对齐 `GlassNavigationBarDefaults.IconSize` |

## 踩坑记录

1. **决定切换位置的动画状态一律放在组件最外层**。
   `BoxWithConstraints` 是 SubcomposeLayout，content 会在重新测量/子组合时重跑，
   里面的 `remember` 有被重建的风险——一旦重建，`Animatable` 以新 index 初始化，
   切换就变成**瞬移**，拉伸动画根本不会播（AVD 逐帧录屏才发现）。
   `DampedDragAnimation`（含位置 Animatable）因此在最外层构造；
   `panelOffset` 的让位 Animatable 与光斑 progress 只影响装饰层、不含 index 初值，
   留在 BWC 内（与 demo 一致）是安全的。

2. **`layerBackdrop` 必须在 `drawBackdrop` 之前**。
   顺序反了录到的是"玻璃之下"的空内容，胶囊折射不到底栏图标。

3. **`drawBackdrop` 的所有 lambda 必须 remember**（含 shape / effects / highlight /
   shadow / innerShadow / layerBlock / onDrawSurface）。
   backdrop 2.0.1 的 `DrawBackdropElement.equals()` 按 lambda **引用**判等，
   `update()` 里无条件调 `invalidateDrawCache()` → 任一 lambda 换身份就会重录整套
   取景链并重模糊，滚动时表现为底栏细抖。捕获了 `damped` / 主题色的 lambda 用
   `remember(damped)` / `remember(isLightTheme, damped)` 固定身份；
   只依赖 Density / Size 的（如 `barEffects`）无 key 即可。

4. **静止态不要出现"第二块玻璃"**。
   灰提示就是胶囊在 p=0 时的表面色；`effects` 里 lens 半径乘 p 归零，
   highlight / shadow / innerShadow 也全部乘 p。任何常驻的折射或模糊都会让它
   看起来像另一块浮起的玻璃，静止态的"无浮起"就没了。

5. **图标高亮跟随胶囊位置而不是目标 tab**。
   否则点了第 5 个 tab，图标瞬间全变、玻璃还在半路，观感割裂。
   用 `derivedStateOf { damped.value.fastRoundToInt() }` 只在跨 tab 边界时重组。

6. **表面色必须淡，且 demo 结构里胶囊常驻在图标之上**。
   早期把灰底塞到图标下、玻璃层压在图标上（两层 + alpha 淡出），只适用于
   只采 pageBackdrop 的旧实现；换成 combined 取景后胶囊必须在图标之上才能把
   底栏图标吸进折射。玻璃表面色一开始给了 65% 白，图标直接"消失"——
   降到 `Black 10% ×(1-p) + Black 3%×p` 才透得出来。

7. **隐形导出层（Row B）不要挂 clickable、不要画表面色、不要整层 tint**。
   它 alpha(0) 盖在整个底栏上：挂空点击 = 真实点按全被拦；画表面色/整层 tint =
   tabsBackdrop 变成实心强调色，胶囊折射成一整块色块（三个都是 AVD 实证踩过的）。

8. **手势收尾要闭环，否则留下半截状态**：
   - 松手必须 `offsetAnimation.animateTo(0f, spring(...))`，否则整条底栏的
     让位偏移会一直累积残留（demo 有这一句，移植时最容易漏）；让位的
     `snapTo` 也要用 Job 跟踪并在回弹前 cancel，否则排队的 snapTo 会覆盖回弹；
   - 松手没换页时 index 不变，必须靠 `NavSettle.serial` 触发一次吸附，
     否则胶囊卡在两 tab 之间；
   - `press` 的落回只交给 `animateToValue`，手势回调别再 launch press 动画；
     位置已在目标时也要把 press 落回 0；
   - 点按不许直接 `onSelect`，统一走状态流，否则上报两次、切页时机与拖动不一致。

9. **拖动停在两 tab 中间，松手必须吸附到最近 tab 并换页**（AVD 实测）。
   症状是胶囊停在松手点不动、页面也不切。根因是 `updateValue` 的
   `position.snapTo` 经 `scope.launch` 排队：松手时最后一个 snapTo 可能尚未执行，
   随后触发的 `animateToValue` 会被这个迟到的 snapTo 打断，连 `onSelect` 一起吞掉。
   五处一起修：
   - `pendingTarget` 用**同步字段**记录拖动意图，`targetValue` 读它而不是
     `Animatable.targetValue`（后者滞后一个事件），吸附目标取 `roundToInt()`；
     该字段只表示「拖动意图」，**不要**在 `animateToValue` 里改成动画目标值；
   - 每次 `updateValue` 先 cancel 排队中的 `snapJob`，snapTo 用
     `CoroutineStart.UNDISPATCHED` 立即落地；`animateToValue` 开头再兜底 cancel 一次；
   - `dragStartSettled` 区分「拖动刚结束」（动画起点 = `pendingTarget` 松手点）与
     「普通点按 / 连续切换」（起点 = `position.value`）。在动画开始前就把
     `pendingTarget` 改成整数目标，会让快速连点时胶囊先回跳到上一个 tab；
   - **拖动开始把 `pendingTarget` 重置为 `position.value`**，下一轮拖动不会继承
     上一轮松手的半程点——那是同一个残留状态换个时机发作；
   - 切换意图用单个 `NavSettle(index, serial)` 一次写一次 emit，避免
     `collectLatest` 取消自己的动画并吞掉 `onSelect`。
   也不要留 `settlePress()` 这类只 `snapTo(pendingTarget)`、不做吸附的兜底函数，
   误用恰好会把胶囊停在中间。

10. **RTL 三处一起翻**：拖动增量、`translationX`、光斑中心。
    只翻一处会出现"手往左拖、胶囊往右走"。坐标系公式见「手势」一节。

11. **验证手段**：动画中间态截图抓不到，用
    `adb shell screenrecord --size 480x1068 --time-limit 3` 录屏 +
    `ffmpeg -i nav.mp4 -vf fps=60 f_%03d.png` 抽帧 + PIL 帧差定位切换区间，
    再逐帧检查胶囊宽度/位置。
