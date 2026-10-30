# Architecture and evidence flow

**Language** · [简体中文](ARCHITECTURE.md) · [English](ARCHITECTURE.en.md)

<p align="center"><img src="assets/androbody-architecture.en.svg" alt="Diagram showing Native, Gateway, and the planned external tool service sharing permission checks, Android calls, receipts, and independent observation" width="640"></p>

<p align="center"><sub>Solid lines show current implementation paths; the dashed external tool service is planned. The diagram is not device-acceptance evidence.</sub></p>

## Product modes

- **Native:** AndroBody owns the conversation, model request, and Android tool loop.
- **Gateway:** a compatible client connects to the local gateway; AndroBody still owns model configuration, tool policy, and local execution.
- **External Tool Service:** planned. An external Harness may call explicitly exposed AndroBody tools, but this does not transfer the app UID, Android permissions, model credentials, or another app's private data.

## Tool execution and evidence

A tool request passes through capability policy, Android permission and runtime-condition checks, an adapter call, and a structured receipt. Receipts distinguish requested, awaiting approval, submitted, completed, unknown, and failed stages. A system accepting a dispatch does not prove that the target app completed its task. Unknown outcomes remain inspectable and are not replayed automatically.

The capability map remains open-ended. Each capability records theoretical potential, current feasibility, real attempts, and observed outcomes separately. Android version, app identity, hardware, exported-component rules, user grants, and authorized bridges can affect feasibility. Static inspection may suggest a path; only an actual attempt can establish a tested blocker.

## Persistence and diagnostics

Native conversations and receipts are stored in app-protected storage. The app-level runtime console records metadata that can correlate requests, sessions, tool calls, and results. It does not read the entire Android system log or other applications' logs.

Ordinary diagnostics exclude credentials, prompts, notification bodies, audio, and file contents by default. Sending content to a model, reading local files, and exporting sensitive data each use an explicit scope and consent path.

## Current implementation boundary

The native app includes device/network/battery inspection, bounded sensor sampling, notification read and publication paths, TTS, bounded local recording, workspace text-file CRUD, encrypted sessions, an app-level runtime console, and a model-compatible Gateway. Every capability remains subject to Android permissions, device conditions, and current validation evidence.

Generic Intent, system speech recognition, Shell/PTY, Shizuku/Root adapters, automatic context compaction and long-term memory, general event triggers, and direct MCP/IPC tool serving remain planned or incomplete. See the [roadmap](ROADMAP.en.md) for sequencing.
