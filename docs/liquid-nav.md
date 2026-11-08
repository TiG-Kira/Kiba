# 液态玻璃底部导航栏 · 实现说明（v2）

> **v2（2026-11-08）：交互状态机整体移植自 [HyperIsland](https://github.com/1812z/HyperIsland)**
> `compose/component/LiquidGlassNavigationBar.kt`（其源头是 compose-miuix-ui 的
> `IosLiquidGlassNavigationBar` / SukiSU-Ultra `FloatingBottomBar`，Apache-2.0）。
> 涉及 `DampedDragAnimation.kt` / `InteractiveHighlight.kt` / `LiquidNavigationBar.kt` 三个文件。
>
> v1 是 Kyant0 官方 demo `LiquidBottomTabs` 的 1:1 移植。v1 有一个用户实证的重大缺陷：
> **按住当前选中的 tab 不产生任何液态动作**（press 挂在底层 Row 上，被 tab 的 clickable
> settle 抢掉），且弹簧感、通透感明显弱于 HyperIsland。v2 的目标是「把对方状态完整拿过来」，
> 而不是只挑几个参数。本文以下章节全部描述 v2。

> 本文只描述 **`MaterialFamily.LIQUID`** 这一条分支。默认柔光玻璃+ / 未开启高级材质时的
> 经典底栏由 miuix `GlassNavigationBar` 承担，改动液态底栏时**不要碰它们**。
>
> 渲染依赖仍是 `io.github.kyant0:backdrop:2.0.1` + `io.github.kyant0:shapes:1.2.1`
> （[AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)）。
> HyperIsland 用的是 miuix `top.yukonga.miuix.kmp.blur.*`，两套取景 API 互不兼容，
> 所以**只搬状态机与观感参数，渲染层做等效适配**。

## 1. 行为规格

| 状态 | 表现 |
|---|---|
| 静止 | 一条强折射玻璃底栏；选中项只有淡灰色提示胶囊（纯色、无特效）+ 图标变色。无浮起玻璃。 |
| **按住当前 tab** | 手指正落在胶囊上 → Initial pass 立即 `press()`：玻璃浮现、横纵不同阻尼地鼓起、镜面光斑亮起；松手等位置收敛后凝回灰提示。**v1 这里完全没反应。** |
| 点击其它 tab | `onClick` 直接上报换页，`selectedIndex` 回流驱动液滴从当前 tab 飞向目标；飞行中玻璃被速度拉长。 |
| 拖动 | 胶囊 `updateValue` 跟手（带一点弹簧），整条底栏 EaseOut 让位最多 4dp；松手吸附最近 tab 并回弹。 |
| 图标高亮 | 跟随胶囊**实际位置**（`round(damped.value)`），不跟随目标 tab。 |

## 2. 状态机：DampedDragAnimation（HyperIsland 完整版）

五条互不合并的 `Animatable` 是"手感"的来源：

| 状态 | 弹簧 | 作用 |
|---|---|---|
| `value` | `spring(1f, 1000f, 0.001f)` | 胶囊位置（tab 下标），紧致临界阻尼 |
| `velocity` | `spring(0.5f, 300f, 0.01f)` | `VelocityTracker` 读真实手指轨迹、归一化到 valueRange 后平滑，驱动拉伸；拖动停止有余韵 |
| `pressProgress` | `spring(1f, 1000f, 0.001f)` | 玻璃浮现 / 凝回，与位置飞行并行启动 |
| `scaleX` | `spring(0.6f, 250f, 0.001f)` | 横向鼓起 → `78/56 ≈ 1.39` |
| `scaleY` | `spring(0.7f, 250f, 0.001f)` | 纵向鼓起，阻尼不同 → **果冻感**（v1 是 `lerp(1, 1.39, press)` 等比线性，毫无弹性） |

- `MutatorMutex` 串行化 `animateToValue`：press → 飞行 → release 是一个整体，新切换不会把
  上一次的玻璃收尾拦腰截断。
- `release()` 先 `withFrameNanos` 稳住第一帧，再
  `snapshotFlow { value }.first { abs(it - targetValue) < 2.5% }` **等飞行真正收敛**才把
  press / scale 落回 0——不是抬手瞬间就把玻璃抹掉。
- `updateValue` 用 `animateTo(..., spring(1f,1000f)) { updateVelocity() }` 跟手，
  不是生硬的 `snapTo`；`velocityTracker.addPosition(SystemClock.uptimeMillis(), Offset(value,0))`。
- **v2 不再有 `dragMotion` 门控**：v1 为了避免"飞行被拉成色块"关掉了飞行拉伸，
  v2 按对方口径由平滑后的 `velocity` 统一驱动，拉伸更连贯。

### 双 pass 手势（inspectDragGestures）

```kotlin
awaitEachGesture {
    val initialDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val down = awaitFirstDown(requireUnconsumed = false)
    onDragStart(down)          // ← 按下即 press()
    onDrag(initialDown, Offset.Zero)
    val up = drag(initialDown.id) { change -> onDrag(change, change.positionChange()) }
    if (up == null) onDragCancel() else onDragEnd(up)
}
```

- `drag()` / `awaitDragOrUp()` 自己实现，不消费事件；配合 `requireUnconsumed = false`
  让胶囊上的「拖拽」与「高光」两个 pointerInput、以及下层 tab 的 clickable 都能收到完整手势。
- `canDrag(position)` 换算手指在整条底栏上的绝对 x，超出 `0..totalWidth` 就停止跟随。
- 该 modifier **挂在胶囊本体**（最上层、覆盖当前 tab），这是「按住当前 tab 有反馈」的根因：
  v1 把 press 挂在 Row A 上，down 会被同 index 的 settle 立刻把 press 归零。

### 切换出口（v2 简化）

- TabContent 的 `onClick` → `onSelect(i)` 直接上报；`LaunchedEffect` 里
  `snapshotFlow { latestSelectedIndexState.value }.collectLatest` 统一驱动 `animateToValue`。
- 拖动松手 → `onDragStopped` 里 `onSelect(target)` + `animateToValue(target)` +
  `offsetAnimation.animateTo(0f, spring(1f,300f,0.5f))`。
- `lastAnimatedState` 去重，避免「吸附 + selectedIndex 回流」播两次玻璃；
  纯按住当前 tab（未换页、胶囊没离开整数位）**跳过** `animateToValue`，只让 `release()` 收尾，
  否则两个 press 动画抢同一个 Animatable 会闪。

> 所有 `latestSelectedIndex` / `latestOnSelect` 必须保存 `rememberUpdatedState` 返回的
> **State 对象**并读 `.value`。用 `by` 解包后再在 remember 构造的 lambda 里捕获，
> 拿到的是构造那一刻的旧值（切页不生效的经典坑）。

## 3. 取景结构（沿用 v1，仍是核心）

```
pageBackdrop     页面内容取景层
tabsBackdrop     Row B 隐形导出层录制的「底栏 + 染色图标」
combined         rememberCombinedBackdrop(page, tabs) —— 胶囊采样两层
```

z 序从下到上：

1. **Row A 可见底栏**：`vibrancy + blur(4dp) + lens(24,24)`，表面 = 容器色 30%，
   常驻镜面高光 `Highlight.Default(alpha=0.75)` + 常驻淡投影 `Shadow(10dp)`；
   `layerBlock` 让整条底栏按压时 `scale = 1 + 16dp/宽`。**不挂任何手势**（v2 起）。
2. **Row B 隐形导出层**：`alpha(0)`、`layerBackdrop(tabsBackdrop)` 必须在 `drawBackdrop` 之前，
   图标染成强调色且按压缩放 `lerp(1,1.2,p)`；`effects` 的 lens 半径乘 pressProgress。
3. **胶囊**：采样 combined，`lens(10dp·p, 14dp·p, depthEffect = true, chromaticAberration = true)`，
   highlight / shadow / innerShadow / 表面强度全乘 p；`layerBlock` 读 `scaleX/scaleY` 与
   `velocity` 做除法对称式拉伸；`onDrawSurface` = 灰提示 `(1-p)` + 白玻璃气泡 `p`。

**Row B 三条铁律**：不画表面色、不整层 `ColorFilter.tint`、不挂 clickable
（会把 tabsBackdrop 染成实心色块 / 拦掉真实点按，均为 AVD 实证）。

## 4. 通透感（对齐 HyperIsland 的参数）

| 项 | v1 | v2 |
|---|---|---|
| 底栏 blur | 8dp（发闷） | **4dp** |
| 容器色 | FAFAFA / 121212 @ 40% | @ **30%** |
| 常驻高光 | 无 | `Highlight.Default(alpha=0.75)` |
| 常驻投影 | 无 | `Shadow(10dp, 亮0.1 / 暗0.2)` |
| 胶囊透镜 | 仅色散 | 加 **`depthEffect = true`** |
| 高光绘制 | `Brush.radialGradient` 盖在内容上 | **AGSL `smoothstep` + `BlendMode.Plus`**，画在内容**之下**，先画光再 `drawContent()` |
| 光斑 | 钉在胶囊中心 | `positionAnimation` 逐帧跟手、松手 spring 回起点；并按 `rememberDeviceTilt()` 的重力方向偏移 |

> HyperIsland 的 `BloomStroke` 双光源高光依赖 miuix 的 `LightPosition`，Kyant0
> `HighlightStyle` 只有 Default/Ambient/Plain，无法直接搬；v2 改为把重力方向叠进
> `InteractiveHighlight` 的光斑中心，达到等效的「镜面主光随倾斜移动」。

`InteractiveHighlight` 与 `DampedDragAnimation` 共用同一个 `inspectDragGestures`，
**不能各写一份同名 private 顶层函数**（同包跨文件同名 private 声明会在 JVM 崩）。

## 5. 参数速查（v2）

| 参数 | 值 |
|---|---|
| 底栏高度 | 54dp（Row B / 胶囊 46dp），与 `GlassNavigationBar` 对齐，切材质零跳动 |
| Row A effects | `vibrancy + blur(4dp) + lens(24dp, 24dp)` |
| Row A 表面 | 亮 FAFAFA 30% / 暗 121212 30% |
| Row A 高光 / 投影 | `Highlight.Default(0.75)` / `Shadow(10dp, 0.1 / 0.2)` |
| Row B effects | `vibrancy + blur(4dp) + lens(24dp·p, 24dp·p)` |
| Row B 图标 | tint `0088FF / 0091FF`，scale `lerp(1, 1.2, p)` |
| 胶囊 lens | `lens(10dp·p, 14dp·p, depthEffect, chromaticAberration)` |
| 胶囊表面 | 灰提示（亮 Black 10% / 暗 White 10%）×(1-p) + 白气泡（亮 38% / 暗 14%）×p |
| 位置 / press | `spring(1f, 1000f)` |
| scaleX / scaleY | `spring(0.6f, 250f)` / `spring(0.7f, 250f)`，目标 78/56 |
| velocity | `spring(0.5f, 300f)`，来自 VelocityTracker |
| 松手回弹 | `spring(1f, 300f, 0.5f)`（整条底栏让位归零） |
| 拉伸 | `scaleX /= 1 - (v/10·0.75).clamp(±0.2)`；`scaleY *= 1 - (v/10·0.25).clamp(±0.2)` |
| 让位 | `4dp × sign(f) × EaseOut(|f|)`，`f = offset / 底栏宽` |
| 图标 / 标签 | 28dp / 11sp，lineHeight 12sp，间距 1dp |

## 6. 仍然有效的踩坑记录

1. **决定切换位置的动画状态一律放在组件最外层**（`BoxWithConstraints` 是 SubcomposeLayout，
   内部 `remember` 可能重建 → 切换变瞬移）。`DampedDragAnimation`、`offsetAnimation`、
   `totalWidthState`、`lastAnimatedState` 全在最外层；测量值用普通可变 `Metrics` 对象桥接。
2. **`layerBackdrop` 必须在 `drawBackdrop` 之前**，否则录到玻璃之下的空内容。
3. **`drawBackdrop` 的所有 lambda 必须 `remember` 固定身份**（shape / effects / highlight /
   shadow / innerShadow / layerBlock / onDrawSurface）。backdrop 2.0.1 的
   `DrawBackdropElement.equals()` 按 lambda 引用判等，`update()` 无条件
   `invalidateDrawCache()` → 任一 lambda 换身份就会重录取景链，滚动时表现为底栏细抖。
   动画值只在 draw / graphicsLayer 阶段读。
4. **含 glass 子树的上游组合严禁收高频 State**：`panelOffset` 用 `derivedStateOf` 投影，
   且只在 `graphicsLayer { panelOffsetState.value }` 里读；`offsetSnapJob` 是普通字段，
   **绝不能是 State**（拖动每帧写会把整页拖进逐帧重组）。
5. **静止态不要出现"第二块玻璃"**：灰提示就是胶囊 p=0 的表面色；胶囊的
   lens / highlight / shadow / innerShadow 全部乘 p。常驻的是**底栏本体**的高光，不是胶囊的。
6. **表面色必须淡，且胶囊常驻在图标之上**：色重了 tag 会被盖没（早期 65% 白实证）。
7. **Row B 不挂 clickable、不画表面色、不整层 tint**（见第 3 节）。
8. **RTL 三处一起翻**：拖动增量、胶囊 `translationX`、光斑中心；缺一个就左右打架。
9. **玻璃淡出时投影要自己跟着淡出**：`ShadowNode` 是独立层，只认 `Shadow.alpha`；
   p=0 时返回 `null` 才不留残影（顶栏侧已验证，底栏胶囊同口径）。
10. **验证手段**：动画中间态单张截图抓不准，用
    `adb shell screenrecord --size 480x1068 --time-limit 3` + `ffmpeg -i nav.mp4 -vf fps=60 f_%03d.png`
    抽帧 + PIL 帧差定位切换区间。也可长按期间并发 `screencap` 抓按压中间态。

## 7. AVD 验证液态档的必做步骤

`DeviceGrade.PASS_SCORE = 55`，AVD 通常只有 42~44，默认截图其实在验证 miuix-glass。
验证前**临时**把 `MainActivity` 的 `materialEnabled` 改成只认 SP / 硬编码 LIQUID：

```kotlin
val family = remember { MaterialFamily.LIQUID }
val materialEnabled = remember { true }
```

装 debug 包实测，**验完务必回滚这几行并重新编译**，且 `adb emu kill` 立刻关模拟器
（AVD 即用即走，长时间驻留有触发整机 BSoD 的风险）。

v2 实测记录（Pixel_10_Pro，2026-11-08）：
- 静止 → 长按当前 tab：第一个 tab 区域平均亮度 206 → 233、平均像素差 ≈ 44/40/38，
  白色液态气泡明显浮现（**v1 缺陷已修**）；
- 点按切 Kiba 页、在当前页长按、横向拖到第 4 个 tab「我」并换页，全部正常；
- logcat 无 `FATAL EXCEPTION` / `ANR in com.kira.kiba.debug`；
- `:app:assembleDebug :app:testDebugUnitTest` 通过，回滚后再次
  `:app:compileDebugKotlin :app:testDebugUnitTest` 通过。
