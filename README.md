# AndroBody

<p align="center"><img src="docs/assets/androbody-mark.svg" width="76" alt="AndroBody A 加圆点标志"></p>

<p align="center"><strong>让 Agent 调用 Android 原生能力，并用回执说明实际走到了哪一步。</strong></p>

<p align="center"><a href="README.md">简体中文</a> · <a href="README.en.md">English</a></p>

<p align="center"><a href="https://arcj137442.github.io/AndroBody/">产品页</a> · <a href="docs/ROADMAP.md">路线图</a> · <a href="docs/ARCHITECTURE.md">架构</a> · <a href="docs/README.md">文档索引</a></p>

<p align="center"><img src="docs/assets/androbody-architecture.zh.svg" alt="原生、网关与规划中的外接工具服务汇入共同权限检查，调用 Android 能力并生成回执的架构示意" width="640"></p>

<p align="center"><sub>架构示意，不是当前 APK 截图或设备验收证据。</sub></p>

AndroBody 是 Android 原生 Agent 与能力层。它让 Agent 在用户授权范围内调用设备与系统功能，并区分理论可能性、当前可行性、真实调用和可核对结果。

## 三种接入方式

| 模式 | 谁负责模型循环 | 状态 |
|---|---|---|
| **Native** | AndroBody | 已实现：本机对话、模型请求与 Android 工具调用 |
| **Gateway** | AndroBody | 已实现：兼容客户端发起请求，由本机路由模型并执行已授权工具 |
| **External Tool Service** | 外部 Harness | 规划中：直接 MCP/IPC 工具服务尚未实现 |

## 当前能力

- 读取设备、电池、网络与有限传感器状态。
- 按 Android Notification Access 权限读取通知，也可发布 AndroBody 通知。
- 录制受限的本地音频并使用 TTS；录音内容默认留在应用私有存储。
- 对工作区文本文件执行增删改查。
- 加密会话、工具回执、运行追踪和可导出的应用级运行控制台。
- 探查并配置 Anthropic Messages 与 OpenAI Chat Completions 兼容路由。

权限开关只说明系统允许某类访问，不代表工具已经调用或目标效果已经发生。结果应显示实际阶段；没有独立证据时保留“未知”。

## 尚未实现或仍待完整验收

通用 Intent、系统语音识别、Shell/PTY、Shizuku/Root 适配、自动上下文压缩与长期记忆、通用事件触发器、直接 MCP/IPC 工具服务仍在路线图中。规划项不会显示为已可用入口。

## 验证边界

当前源码对应 `0.6.9` 内部验证候选；APK 使用 Android debug 证书，不是正式稳定版，也未随公开仓库发布。既有构建记录包含两个身份变体各 92 项 JVM 测试、lint 与 APK 组装；Android instrumentation 未运行。这些是先前的内部构建记录，本次文档更新没有重跑；原始设备日志不随公开仓库发布。真实模型计费、ARM64 runtime、通知读取和人工视觉/音频验收须按设备与配置单独确认。

## 构建

Windows PowerShell，使用项目自带 Gradle wrapper：

~~~powershell
android\gradlew.bat -p android -PtestBuildType=verification :app:assembleAndrobodyVerification
android\gradlew.bat -p android -PtestBuildType=verification :app:assembleLegacyMigrationVerification
~~~

验证变体使用独立 application ID 与 debug 签名。它们不是生产发布包。

## 文档

- [能力路线图](docs/ROADMAP.md) · [English](docs/ROADMAP.en.md)
- [架构与证据流](docs/ARCHITECTURE.md) · [English](docs/ARCHITECTURE.en.md)
- [可检查委托合同](docs/architecture/INSPECTABLE_DELEGATION.md) · [English](docs/architecture/INSPECTABLE_DELEGATION.en.md)
- [第三方依赖许可证](docs/licenses/README.md) · [English](docs/licenses/README.en.md)

AndroBody 尚未声明项目级软件许可证。公开可见不等于授予复制、修改或再分发权限；各依赖仍按其自身许可证处理。
