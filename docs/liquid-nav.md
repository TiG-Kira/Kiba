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

> **v2.1（2026-11-11）：「按下即飞」≠「按下即切」**
> 指示器的玻璃浮现 / 液滴飞行仍严格发生在 **down 那一帧**，但**页面切换推迟到抬手之后**，
> 且按「液滴最终所在的 tab」提交。按下期间顶栏、页面内容、列表位置全部保持原状。
> 涉及 `DampedDragAnimation.kt` / `LiquidNavigationBar.kt` 两个文件，详见第 2 节与坑 15/16。
>
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
| 点击其它 tab | **按下（Initial pass）即刻** `animateToValue`：玻璃浮现与液滴飞行并行启动；**页面不切**。抬手后按液滴最终落点 `commitSelection`（见第 2 节「切换出口」）。按住期间玻璃保持，松手才凝回灰提示。**飞到位后手指仍可继续左右拖动**，液滴跟手越过任意 tab，松手吸附最近者（2.3.21 补回 v1 兼容），此时才按吸附落点切页。 |
| 拖动 | 胶囊 `updateValue` 跟手（带一点弹簧），整条底栏 EaseOut 让位最多 4dp；松手吸附最近 tab 并回弹，**同一次抬手**按该落点切页。 |
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
  上一次的玻璃收尾拦腰截断；外层 `launch(start = UNDISPATCHED)` 让按下事件当帧就把
  press / 飞行两条 Animatable 排进队列，不多等一次协程调度帧。
- `release()` 先 `withFrameNanos` 稳住第一帧，再
  `snapshotFlow { value }.first { abs(it - targetValue) < 2.5% }` **等飞行真正收敛**才把
  press / scale 落回 0——不是抬手瞬间就把玻璃抹掉。`holdPress()` 为 true（手指仍按在
  目标 tab）时继续维持玻璃直到抬手，60 帧兜底防 up 事件丢失导致永不散。
  末尾 `onGestureSettled` 只做位置自愈 / 落点观测，**不再切页**（v2.1，见坑 15）。
- `updateValue` 用 `animateTo(..., spring(1f,1000f)) { updateVelocity() }` 跟手，
  不是生硬的 `snapTo`；`velocityTracker.addPosition(SystemClock.uptimeMillis(), Offset(value,0))`。
- `settleTo(target)` 只收敛位置 + 清速度，**不重启 press / release**：按住拖动松手时
  玻璃收尾仍归按下链那次 `release`，再播一次 press 会抢同一条 Animatable 导致明暗闪。
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

### 起飞出口 / 切换出口（v2.1，2026-11-11：起飞在 down，切页在 up）

历史背景：v2 初版把点按做成「抬手 `onClick` → `onSelect` → 等父级 `selectedIndex` 回流 →
`snapshotFlow.collectLatest` → `animateToValue`」，真机实测比 2.3.18 慢半拍：多花抬手、
父级重组、快照流好几帧，液滴要等页面切过去才起跑。胶囊手势只压在**当前 tab**上，
点其它 tab 时它收不到 down，所以 Row A 必须有一个独立的**即时起飞入口**。
v2.1 把「即时」拆成两件事：**动画仍在 down 当帧起跑，页面改为抬手后提交**。

- **Row A 链尾** `pointerInput(dragAnimation, items.size)`：
  `awaitFirstDown(requireUnconsumed = false, pass = Initial)` 按 down.x 算出 hit tab，
  `hit != selectedIndex` 时同一帧做 `lastAnimatedState = hit`（去重）→
  `holdingOtherTab = true` → `animateToValue(hit) { holdingOtherTab }`，**不调 onSelect**。
  随后继续用 Initial pass 观察 move / up：按住期间把 `dx / tabWidth` 累加到局部
  `dragBase` 并 `updateValue` 跟手，整条底栏 `offsetAnimation` 同步让位；
  首帧 `dragBase` 取胶囊**真实视觉位置**（起飞动画可能已飞出半程，用按下点起跳会回跳），
  且首帧只定基准不叠位移。全程**不 consume**，TabContent 的 clickable 照常收抬手事件。
- **抬手统一出口** `commitSelection(target)`：先写 `lastAnimatedState`（防父级回流时
  `snapshotFlow` 重播起飞），再在 `target != selectedIndex` 时调 `onSelect`。
  `target` 取「液滴最终所在 tab」：拖动过 = `dragTarget.roundToInt()` 吸附落点并 `settleTo`；
  纯点按 = 起飞目标 `hit`，若抬手位置已跨到相邻 tab（贴边界按下、位移未过 touchSlop）
  则以 `lastX` 换算的 tab 为准并 `settleTo` 修正。让位回弹照旧。
- **纯点按（含快速点按）绝不进拖动分支**：up 事件 `change.pressed == false` 必须先 break；
  否则起飞动画半程会被 `round` 吸附到中途 tab，快速连点直接切错页。
- **clickable 抬手回声**：同一个 up 还会触发 `TabContent.onClick`，它拿到的 `i` 是
  **按下时**的 tab，拖动 / 贴边按下时与最终落点不同。`metrics.pointerCommitIndex/At`
  在提交时打标（250ms 短窗），`onClick` 认出回声就只消费标记、不重播动画也不切页；
  没有标记（键盘 / 无障碍，无 down）才按 `i` 驱动 `animateToValue` 并切页。
- **`onGestureSettled` 不再切页**（见坑 15）：`release()` 收敛时机晚于抬手，
  `holdPress` 还有 60 帧超时，长按未松手会提前切页；读到飞行中途值还会覆盖落点。
- `LaunchedEffect` 的 `snapshotFlow { selectedIndex }` 只作**外部入口**（其它代码改 tab），
  靠 `lastAnimatedState` 去重，不负责切页。
- 胶囊手势拖动松手 → `onDragStopped` 里 `commitSelection(target)` + `settleTo(target)` +
  `offsetAnimation.animateTo(0f, spring(1f,300f,0.5f))`；同样打回声标记。
  纯按住当前 tab 跳过 `animateToValue`，只让 `release()` 收尾，否则两个 press 动画
  抢同一 Animatable 会闪。

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
11. **点按起飞不能等 `selectedIndex` 回流**：父级改 tab 会连带整页重组，
    `snapshotFlow` 还要等下一帧快照，叠加起来就是「按下 → 液滴起跑」的可见延迟。
    胶囊手势覆盖不到其它 tab，即时入口必须挂在 Row A 的 Initial pass，
    再用 `lastAnimatedState` 与回流去重（v2.3.19 真机实测慢半拍，v2.3.20 修）。
    **v2.1 补充**：起飞仍严格在 down 当帧；移到 up 的只有 `onSelect`。别为了
    「等抬手」顺手把 `animateToValue` 也推迟，否则又退回 v2 初版的慢半拍。
12. **「按下即飞」后必须保留按住拖动**：只等 up 的观察器会让液滴到位后锁死
    （v2.3.20 真机反馈）。首帧基准取真实视觉位置（否则回跳）、纯点按的 up 提前
    break（否则飞行半程被吸附到中途 tab），两条缺一不可。
13. **轻扫卡在两 tab 之间（v2.3.21 真机，极低概率）**：`updateValue` 每帧 `launch`
    一个独立协程写 `valueAnimation`，末帧可能排到手势收尾之后执行，把 `targetValue`
    改回半程小数；而 `onDragStopped` 用瞬时 `value` 判断「已就位」并跳过收敛，
    于是胶囊永久停在中间、`onSelect` 又因 `target == selectedIndex` 被跳过。
    修法：所有位置写入收敛到唯一入口 `animateValue()`（先 `valueJob?.cancel()` 再
    `UNDISPATCHED` 启动），用同步记账的 `pendingTarget / dragTarget` 读落点，
    `onDragStopped` **无条件**走提交 + `settleTo`；`release()` 末尾只做位置自愈
    （v2.1 起不再借 `onGestureSettled` 切页，见坑 15）。
14. **松手带速度时胶囊形状变形**：速度弹簧（`spring(0.5f,300f)`）衰减比玻璃凝回慢，
    残余 `velocity` 会继续横向拉伸静止形状，最右 tab 直接顶出底栏圆角。
    形变必须按 `pressProgress` 加权——`lerp(1f, 变形值, press)`，p=0 时严格 1:1。
15. **`onGestureSettled` / release 兜底不能在按住期间切页（v2.1）**：
    `release(holdPress)` 有 60 帧（约 1s）超时，长按不松手时它照样会跑到末尾；
    若在 `onGestureSettled` 里 `onSelect`，页面会在手指还按着的时候切走。
    且它读的是 `valueAnimation.value.roundToInt()`（可能是飞行中途值），拖动后还会
    把最终落点覆盖成中途 tab。切页必须只由抬手出口 `commitSelection` 负责，
    该回调退化为 no-op / 位置自愈观测点。
16. **clickable 抬手回声的 `i` 不是最终落点（v2.1）**：Row A 观察器在 Initial pass
    处理 down / up，TabContent 的 `clickable` 在同一个 up 的 Main pass 还会回调一次
    `onClick`，它闭包里的 `i` 是**按下时**的 tab。拖动到别的 tab 松手、或贴着 tab
    边界按下抬手跨到相邻 tab 时 `i != 落点`，若照旧 `onSelect(i)` 会把刚提交的
    页面拽回去。用 `pointerCommitIndex/At` 打 250ms 短窗标记认出回声并跳过；
    没有标记（键盘 / 无障碍，无 down）才按 `i` 驱动飞行并切页。短窗同时避免
    残留标记影响后续键盘点击。
17. **Git Bash 会把设备端路径当本机路径转换（v2.1 取证踩坑）**：
    `adb shell uiautomator dump /sdcard/w.xml` 里以 `/` 开头的参数会被 MSYS 转成
    `D:/.../PortableGit/sdcard/w.xml`，dump 看着"成功"其实读不回来（前三轮验证
    全部空输出就是这个原因）。设备命令整体用单引号包住：
    `adb shell 'uiautomator dump /sdcard/w.xml'` / `adb exec-out 'cat /sdcard/w.xml'`；
    本地落盘一律用绝对路径（`tmp_avd/` 在工作区根，不在 `kiba/` 仓库根内）。

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

2.3.20 修订实测（Pixel_10_Pro，2026-11-08，5 tab 底栏 bounds `[72,2622][1208,2784]`）：
- 长按「我」tab（x≈863, y≈2703）1500ms，0.5s 时抓图：该 tab 区域平均亮度
  idle 217.6 → 按住 229.6（白气泡已浮现）→ 松手 206.3（凝回灰提示）；
  证明**手指还按着液滴就已经飞到目标处**，且 hold 生效；
- 长按 0.4s 时的 `uiautomator dump` 顶栏已是「我」→ 页面与液滴在 down 同步切换
  （**历史行为**，v2.1 起页面改为抬手后切页，这条记录只说明当时起飞与切页都在 down）；
- 快速连点「曲目」→「设置」最终停在「设置」；从「设置」横拖到「曲目」
  吸附回「曲目」，胶囊手势未被新增观察器破坏；
- logcat 无 `FATAL EXCEPTION` / `ANR in com.kira.kiba.debug`；
- 验完 `adb emu kill` + 残留 emulator 进程清干净，临时闸门回滚后再次编译单测通过。

2.3.21 修订实测（Pixel_10_Pro，2026-11-08）：用 `input motionevent DOWN/MOVE/UP`
精确拼手势（比 `swipe` 更能表达「按住等飞到位再拖」）：
- 在「曲目」DOWN 到「我」(863,2703) → 停 0.6s 等液滴到位 → 连续 MOVE 到
  「设置」(1085) → UP：最终顶栏「设置」，页面随拖动换到 index 4；
  截图亮度「我」229.6 → 218.6（-11）、「设置」仍为三个区域最高 225.6，
  证明玻璃确实跟着手指移出原位；
- 快速点按（DOWN 立刻 UP）→「曲目」、纯点按停 0.5s →「我」、
  反向拖（从「听」按到「Kiba」）→「Kiba」，均正确；
- logcat 无 `FATAL EXCEPTION` / `ANR in com.kira.kiba.debug`；
- 验完 `adb emu kill` + 残留进程清干净、临时闸门回滚，编译单测通过。

v2.1 修订实测（Pixel_10_Pro，2026-11-11，1280x2856；5 tab 中心 y≈2703、
x≈186/413/640/863/1085）。用 `input motionevent DOWN/MOVE/UP` 拼手势，
每个状态各做一次 `uiautomator dump`，按页面特征文字判当前页
（曲目 = 歌曲列表；「我」= 查分器/成绩查询；「设置」= 外观/主题/真·高级材质）：

- baseline「曲目」→ 按住「我」(863,2703) **2 秒**（故意超过 `holdPress` 的
  60 帧超时）→ dump 仍是同一组歌曲列表，XML 与 baseline 只差 1 字节：
  **按住期间页面完全没切**，也证明 `onGestureSettled` 不再趁长按提前切页；
- UP → 变「我」页：**松手才切页**；
- 从「曲目」(186) DOWN → 等液滴到位 → 连续 MOVE 到最右 → UP → 变「设置」页：
  **按拖动最终落点切页**，clickable 抬手回声没把页面拽回按下时的 tab；
- 快速点按「曲目」→ 回曲目页：纯点按未受「等抬手」影响；
- logcat 无 `FATAL EXCEPTION` / `ANR in com.kira.kiba.debug`；
- 验完 `adb emu kill`，`tasklist` 无 emulator/qemu 残留；临时闸门已回滚
  （`git diff MainActivity.kt` 为空），回滚后 `:app:compileDebugKotlin
  :app:testDebugUnitTest` 通过。取证原始 XML / 截图留在工作区 `tmp_avd/nav_*.png|xml`。
