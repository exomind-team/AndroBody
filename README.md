# 安卓之身（AndroBody）

**让 Android 原生能力可以被 AI Harness 按权限调用，并用同一条回执链核对实际结果。当前外部客户端通过本机兼容网关接入。**

[产品页](https://arcj137442.github.io/AndroBody/) · [GitHub 源码](https://github.com/exomind-team/AndroBody) · [路线图](docs/ROADMAP.md) · [架构](docs/ARCHITECTURE.md) · [文档索引](docs/README.md)

**English:** AndroBody provides permissioned native Android capabilities through a local model-compatible gateway and records evidence that distinguishes requests, system responses, and observed results.

## What AndroBody does

AndroBody is a native Android app and an Android capability layer. Its app identity holds Android permissions; registered tools let an agent request supported device and system functions. Each call passes capability policy and permission checks, then returns a structured receipt to the encrypted conversation and the app-level runtime console.

The project keeps three facts separate:

- **Potential:** what Android APIs, hardware, components or authorized bridges could theoretically enable.
- **Feasibility:** what this device, Android version, app identity and current user grants allow.
- **Attempt and result:** what was actually called, what responded, and what independent evidence confirms.

An unimplemented or untested capability stays visible as such. Static inspection alone does not prove that a device action is blocked or completed.

## Current integration paths

| Path | Who owns the model loop | Current status |
|---|---|---|
| **Native** | AndroBody | Implemented: local conversations, configured model requests and native Android tools. |
| **Gateway** | AndroBody | Implemented: a local model-compatible gateway accepts external client requests; AndroBody routes to its configured upstream and executes only the tools the user has enabled. |
| **External Tool Service** | An external Harness | Planned: direct MCP/IPC tool access is not implemented. The gateway is not an MCP server and does not grant another app AndroBody's UID or Android permissions. |

## Current capabilities

- Device, battery and network state; bounded sensor sampling.
- Notification publication and, with Android Notification Access, reading permitted notifications.
- Text-to-speech and bounded local microphone recording. Recordings remain app-private; the agent receives artifact metadata, not audio bytes.
- A private text workspace for creating, reading, editing and deleting files.
- Encrypted native sessions, tool receipts, request/trace diagnostics and an exportable runtime console.
- Anthropic Messages and OpenAI Chat Completions connection discovery, model catalogs and configuration checks.

## Current limits

The Android capability catalog remains open-ended; current research lists are indexes, not a complete map. Generic Intent dispatch, speech recognition (ASR), direct external MCP/IPC tools, Shell/PTY, automatic context compaction and long-term memory, and general event-trigger management are not currently implemented or fully verified. The [roadmap](docs/ROADMAP.md) tracks planned Harness workflows, capability coverage, feasibility checks and evidence boundaries.

Android permissions do not transfer between package identities. Notification Access, microphone, overlay and other system permissions must be checked and granted by the user to the installed AndroBody package. The app does not use ADB to grant them.

## Ecosystem position

| Project | Main work surface | How it relates to AndroBody |
|---|---|---|
| [AndroidHarness](https://github.com/Sanuu7/AndroidHarness) | Full mobile coding Harness: project files/editor, Shell, Git, browser, MCP, models and resumable agent workflows | AndroidHarness manages the project and Harness experience; AndroBody focuses on permissioned Android-native capabilities and result evidence. Direct tool-server integration is still planned. |
| [DroidRun / Mobilerun](https://github.com/droidrun/mobilerun) | Cross-app UI automation and device workflows | AndroBody currently uses native Android APIs rather than general accessibility-driven UI control. |
| [Tasker](https://tasker.joaoapps.com/) | User-configured conditions, events and actions | General agent-managed triggers are a future AndroBody capability. |
| [Termux](https://termux.dev/en/) / [Shizuku](https://shizuku.rikka.app/introduction/) | Linux userland / an authorized bridge to selected Android APIs | Adjacent execution environments; AndroBody's own Shell/PTY and Shizuku adapters are not yet implemented. |

## Verification status

The current `0.6.9` build is an **internal verification candidate**, signed with an Android debug certificate; it is not a public stable release. Both identity variants have 92 JVM tests, Verification lint and APK assembly recorded as passing. Android instrumentation was assembled but not run. Same-device migration and session readback were verified on an API 35 Android tablet; uninstalling the target package and restoring from the legacy package remains `NOT_RUN`.

Real model use and billing, ARM64 runtime, Android permission re-grants, ASR, real third-party notification capture, and human visual/audio acceptance remain unverified. Build-specific evidence is kept separately from this public overview. The roadmap describes plans, not completed or verified behavior.

## Build for authorized collaborators

The Android application module is in `android/native-app`; the project uses Android SDK 35. From an authorized checkout on Windows:

```powershell
android\gradlew.bat -p android -PtestBuildType=verification :app:assembleAndrobodyVerification
android\gradlew.bat -p android -PtestBuildType=verification :app:assembleLegacyMigrationVerification
```

AndroBody has not declared a software license. Public repository visibility does not grant permission to redistribute or modify its source or APK. Do not publish private credentials, signing keys, device backups or raw user diagnostics.
