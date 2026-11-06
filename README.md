<div align="center">

# Kiba

**舞萌 DX 曲库助手 · Android**

基于 [MaimaiData](https://github.com/PaperPig/MaimaiData) 数据核心的 Jetpack Compose 二次开发，
UI 采用 [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) 玻璃拟态体系。

[![Release](https://img.shields.io/github/v/release/TiG-Kira/Kiba?include_prereleases&logo=github)](https://github.com/TiG-Kira/Kiba/releases)
[![CI](https://github.com/TiG-Kira/Kiba/actions/workflows/ci.yml/badge.svg)](https://github.com/TiG-Kira/Kiba/actions/workflows/ci.yml)
![minSdk](https://img.shields.io/badge/minSdk-33-3DDC84?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin)

</div>

---

## 功能

- **曲库**：全量曲库本地同步（Room），支持按流派/版本/等级筛选、别名搜索；断网可查。
- **听**（乐曲中枢页）：
  - **智能推荐**：基于 Best 成绩与收藏画像选曲，点击即播，无需手动建队列；
  - **收藏连播**：收藏曲目一键串烧；
  - **我的歌单**：自建歌单（曲目详情页一键入单），点歌即播 / 整单播放；
  - **Agent 专属歌单**：配置 LLM 后按你的口味从候选曲池精选（≤15 首，默认 24h 自动刷新、可调）；
  - **我的下载**：歌曲下载到本地存储（含 ID3 标题/艺术家/封面对齐详情口径），随时试听或删除；
  - 无音源曲目自动提示并跳过，不打断播放链。
- **乐曲播放器**：QQ 音乐式沉浸页（封面即背景、控制区五键等距分布），单曲/列表循环，
  窄屏自适应（优先保证媒体控制可用）；媒体通知带真时长进度条、上下曲、
  **快速收藏**与关闭按钮（Android 13+ 媒体卡片原生渲染），播完不撤通知。
- **歌曲详情**：玻璃拟态魔改顶栏（封面作背景、亮度自适应字色）、谱面定数一览、收藏、
  谱面预览（落雪官方谱面播放器）。
- **真·高级材质**：设备评分达标后可选两档风格——
  **柔光玻璃+**（miuix 取景柔化，折射收敛、滚动最稳，默认）与
  **液态玻璃**（[AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 独立控件：
  胶囊底栏 + 滑动指示器、滚动后整块玻璃从顶边包住状态栏、贴底弹层带色散折射）。
  两档只作用于顶栏 / 底栏 / 播放底栏 / 弹层，列表卡片保持不透明——
  玻璃叠在高对比的列表内容上只会糊成一片，反而更难读。
- **查分双源**：水鱼查分器 / 落雪 API 二选一（设置页切换），落雪另提供官方代理同步与
  OAuth 登录通道；NET/代打口径对齐 maimaidx-prober。
- **Best 50**：绑定[水鱼查分器](https://www.diving-fish.com/maimaidx/prober/)后一键生成成绩单图片（B50），坐标与样式对齐 KibaBot。
- **Agent**：接入任意 OpenAI 兼容 LLM（流式输出 + 深度思考分卡展示；输出被截断会自动续写，
  工具链最多 8 轮直到模型自己收尾，不会「说着说着突然停」），可调用内置本地技能：
  推分推荐 / 随机选曲 / 定数查歌 / 分数线 / 随机条件曲 / 猜歌 / 今日运势……（技能逻辑移植自 [KibaBot](https://github.com/KilluaSakata/Kiba)，宴会场谱面自动排除）。
  每轮**实际调用**了哪些技能会以「技能调用」卡片列出（技能名 / 参数 / 结果摘要，可折叠）；
  机制类问题（定数 / Rating 公式 / 判定档位 / 段位 / 术语）优先查**内置的萌娘百科知识库**
  （舞萌DX / 舞萌系列 / CHUNITHM 三个条目，离线可用、不会编造）。
- **进度统计**：版本进度 / 等级进度，下钻明细支持判定筛选（极 / 将 / 神 / 评级细档），未完成置顶、已完成垫底。
- **中二节奏**：平行曲库 / 成绩 / 进度体系（谱面物量补全、落雪封面源），与舞萌侧数据互不干扰。
- **桌面小组件**：曲库速查。
- **应用内更新**：检查 GitHub Releases 下载 APK（SHA-256 / 包名 / 版本 / 签名多重校验），Markdown 渲染更新日志。

## 下载

前往 [Releases](https://github.com/TiG-Kira/Kiba/releases) 下载 `app-release.apk`，
或在 App 内「设置 → 关于 → 检查更新」直接升级。

## 构建

```bash
git clone git@github.com:TiG-Kira/Kiba.git
cd Kiba
./gradlew :app:assembleDebug
```

要求：JDK 21 · Android Gradle Plugin 9.1.0 · compileSdk 37 · minSdk 33（Android 13+）。

签名配置（可选，CI 用）：`KIBA_KEYSTORE_BASE64 / _PASSWORD / _ALIAS / _KEY_PASSWORD`。

## 技术栈

Kotlin · Jetpack Compose · miuix 0.9.4（+ vendored miuix-glass 玻璃顶栏/底栏）·
Room · Coil（256MB 磁盘缓存）· OkHttp · Coroutines · DataStore/SpUtil。

## 数据来源与免责声明

- 曲库数据：MaimaiData 更新源；封面：落雪 lxns / 水鱼 CDN / 官网图床多级候选；
  成绩：水鱼查分器 / 落雪 API；乐曲音源：落雪整轨 CDN。
- 本项目与世嘉（SEGA）、华立科技无关，仅供学习交流，请勿用于商业用途。
- 游玩记录与账号信息仅存储在本地。

## 致谢

- [MaimaiData](https://github.com/PaperPig/MaimaiData) —— 数据核心与交互蓝本
- [KibaBot](https://github.com/KilluaSakata/Kiba) —— Agent 技能与 B50 图片逻辑来源
- [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) —— UI 组件库
- [水鱼查分器](https://www.diving-fish.com/maimaidx/prober/) / [落雪 API](https://maimai.lxns.net/) —— 成绩与音源数据
