# AndroBody architecture

## Product modes

- **Native:** AndroBody owns the conversation, configured model request and Android tool loop.
- **Gateway:** an external model-compatible client connects to the local Gateway. AndroBody still owns its upstream model configuration, capability policy and local Android execution.
- **External Tool Service:** planned. An external Harness would call explicitly exposed AndroBody tools; this does not transfer the app UID, Android permissions, model credentials or access to another app's private data.

## Tool execution and evidence

A tool request passes through a single capability policy, checks the relevant Android permission and runtime condition, invokes the adapter, and returns a structured receipt. Receipts distinguish stages such as requested, awaiting approval, submitted, completed, unknown and failed. A system accepting a dispatch does not prove that the target app completed its task. An unknown result must remain inspectable and must not be replayed automatically.

The capability map remains open-ended. For each capability, the product keeps theoretical potential, current feasibility, actual attempts and observed outcomes separate. Android version, app identity, hardware, exported-component rules, system grants and authorized bridges can change feasibility. Static analysis can suggest a route; only a real attempt can establish a tested blocker.

## Persistence and diagnostics

Native conversations and receipts are stored within the app's protected storage. The app-level runtime console records operational metadata that helps connect a request, session, tool call and result. It is not a reader for the entire Android system log or other applications' logs.

Credentials, prompts, notification bodies, audio and file contents are excluded from ordinary diagnostics by default. Any feature that sends content to a configured model or exports sensitive data must use its own explicit scope and consent path.

## Current implementation boundary

The native app includes device/network/battery inspection, bounded sensors, notification read and publication paths, text-to-speech, bounded local recording, workspace text-file CRUD, encrypted sessions, an app-level runtime console and model-compatible Gateway routes. Each capability still depends on its Android permission, device condition and current validation evidence.

Generic Intent dispatch, system speech recognition, Shell/PTY, Shizuku/Root adapters, automatic context compaction and long-term memory, general event-trigger management, and direct MCP/IPC tool serving remain planned or incomplete. See the [roadmap](ROADMAP.md) for the intended sequence.
