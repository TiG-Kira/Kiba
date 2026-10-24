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
- **歌曲详情**：玻璃拟态魔改顶栏（封面作背景、亮度自适应字色）、谱面定数一览、收藏。
- **Best 50**：绑定[水鱼查分器](https://www.diving-fish.com/maimaidx/prober/)后一键生成成绩单图片（B50），坐标与样式对齐 KibaBot。
- **Agent**：接入任意 OpenAI 兼容 LLM（流式输出 + 深度思考分卡展示），可调用内置本地技能：
  推分推荐 / 随机选曲 / 定数查歌 / 分数线 / 随机条件曲 / 猜歌 / 今日运势……（技能逻辑移植自 [KibaBot](https://github.com/KilluaSakata/Kiba)，宴会场谱面自动排除）。
- **进度统计**：版本进度 / 等级进度，下钻明细支持判定筛选（极 / 将 / 神 / 评级细档），未完成置顶、已完成垫底。
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

- 曲库数据：MaimaiData 更新源；封面：水鱼 CDN / maimai 官网图床；成绩：水鱼查分器。
- 本项目与世嘉（SEGA）、华立科技无关，仅供学习交流，请勿用于商业用途。
- 游玩记录与账号信息仅存储在本地。

## 致谢

- [MaimaiData](https://github.com/PaperPig/MaimaiData) —— 数据核心与交互蓝本
- [KibaBot](https://github.com/KilluaSakata/Kiba) —— Agent 技能与 B50 图片逻辑来源
- [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) —— UI 组件库
- [水鱼查分器](https://www.diving-fish.com/maimaidx/prober/) —— 成绩数据
