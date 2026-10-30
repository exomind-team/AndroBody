# AndroBody roadmap

**Language** · [简体中文](ROADMAP.md) · [English](ROADMAP.en.md)

**Updated:** 2026-10-30. This document describes future work; it does not claim that planned behavior is implemented or accepted.

## Invariant development principle

AndroBody must keep an open map of what Android applications could theoretically sense and do. Low frequency, implementation effort, missing grants, or temporary device limitations are not reasons to remove a capability from the design.

For each capability, keep four dimensions distinct:

1. **Theoretical potential:** what Android APIs, hardware, components, app-local runtimes, or authorized bridges may offer.
2. **Current feasibility:** what this system version, device, app identity, installed components, and user grants allow.
3. **Real attempt:** which call was actually made and how far it progressed.
4. **Observed result:** what the system or target app returned and what independent evidence confirms.

A blocker becomes a tested conclusion only after a real attempt. Static inspection creates a hypothesis, not proof. Missing, denied, unimplemented, untested, unknown, and successful states stay separate. Relevant evidence should be traceable and usable for diagnosis and regression.

General tools are an extensible foundation. Specialized tools and UI add a clear purpose on top of them, reusing the same Kotlin implementation, permissions, and receipts. A specialized description cannot invent underlying capability or change Android policy.

## Current baseline

- Native mode owns the model conversation and tool loop. Gateway mode accepts compatible client requests while AndroBody uses its configured model and enabled tools. The External Tool Service is not implemented.
- Source includes notification read/publication paths, bounded sensors, device/battery/network inspection, TTS, bounded local recording, workspace text-file CRUD, session management, and the app-level runtime console. Each still needs validation against its actual permissions, device conditions, and evidence boundary.
- Read-only context inspection exists. Automatic compaction, reviewable long-term memory, system speech recognition, generic Intent, full Shell/PTY, Shizuku/Root adapters, general Agent-managed triggers, and direct MCP/IPC serving remain planned or incomplete.
- AndroidHarness is the primary reference for Harness workflow and phone/tablet adaptation. Zafiro is a secondary reference for streaming and session continuity. Adopt patterns that fit AndroBody's permission and evidence contracts; do not present unimplemented or unreleased experiments as available.

## Phased plan

### 1. Session resilience and core Harness operations

Prevent longer tasks from dead-ending after interruption or an unknown device result. Preserve session identity across queueing, stop, resume, branch, rename, archive, deletion, and process recovery. Show running work, approval waits, and partial results. Stopping prevents later work where possible; it does not erase side effects that already occurred. Reconcile unresolved receipts before resuming and never replay them automatically.

**Acceptance focus:** multiple tool rounds, batches, long runs, cancellation, process death, unknown results, network recovery, and model switching preserve session/request/trace identity. The user can continue, inspect, and recover after failure.

### 2. Open-world capability map and feasibility checks

Let the agent query capabilities by device, Android API, app identity, and authorized bridge. Entries describe candidate interfaces or executors, required permissions, unknown feasibility, real attempts, system receipts, independent observations, and evidence references. The catalogue can grow across hardware, Android APIs, app components, and lawful bridges; today's registered tools are not the ceiling.

**Acceptance focus:** distinguish not catalogued, not implemented, not granted, infeasible in this environment, tested blocker, attempted but unknown, and independently observed completion. Static analysis cannot declare that no legal path exists.

### 3. Generic Intent and notification actions

Build a general Intent constructor and dispatcher that can represent Android Intent semantics: action, data URI, MIME type, categories, explicit component/package, flags, typed extras, ClipData and URI grants, selector, result routing, and extensible fields. Activity, Broadcast, Service, foreground service, Chooser, and PendingIntent forms should be queryable and expressible, or report their current serialization/system boundary. Rarely used fields are not omitted merely for convenience.

Background launch limits, exported-component rules, signature permissions, target-app protocols, missing components, and confirmation are feasibility and receipt states, not reasons to design away a capability. When Java objects, Binder, or target-specific protocols cannot be represented, record the concrete limitation and retain extension points. Common actions can specialize the same Kotlin dispatcher into launch-app, open-link, share, or calendar-draft tools.

Notification access covers both reading and publication. The Body surface should show actually observed notifications; the agent reads only within granted scope. Publication may compose title, body, style, buttons, and supported actions. Posting, system display, user click, and target-app task completion are separate states. Notification content is untrusted input; model transmission has its own policy.

**Acceptance focus:** independent samples for Activity/Broadcast/Service and each supported Intent field, including non-exported components, missing apps, denied permissions, background limits, Chooser, URI grants, system results, and cancellation. A dispatch accepted by Android is not reported as business completion.

### 4. Shell/PTY and authorized Android bridges

Provide a controlled Shell/PTY for capabilities available to AndroBody's own process/runtime, then explore Shizuku, Root, and other lawful bridges under the same authorization and receipt contract. Do not pre-exclude a route because it is an app-internal shell: catalogue the theoretical path and probe actual UID, visible files, commands, environment, permissions, and restrictions. Test third-party non-exported components before calling them blocked.

Expose execution identity, working directory, file/network scope, start/stop state, exit status, output paths, and revocation. People can set approval, allow, and deny rules for higher-impact actions. Evidence of a tool response does not mean a side effect was rolled back.

**Acceptance focus:** start/stop, concurrency, disconnection, timeout, permission revocation, process recovery, file isolation, network boundaries, output redaction, and recovery across host/emulator/physical-device evidence.

### 5. Speech and local audio artifacts

Complete both input and output: Android `SpeechRecognizer` returns editable text to the current model; recording can start, stop, play, and save, with an authorized local artifact reference for the agent; TTS reads responses aloud. Recording, recognition, playback, and future Omni multimodal transport are separate capabilities with separate permissions and results. Keep a binary-audio model adapter extension point without requiring text-only models to consume audio.

**Acceptance focus:** cancellation and artifact recovery, authorized agent access, editable transcripts, no audio/transcript text in ordinary logs, and diagnosable engine absence, microphone denial, background limits, and readback failure.

### 6. Context compaction, archive, and long-term memory

Build recoverable summary checkpoints and on-demand source recall on top of read-only usage inspection. A summary is an inspectable derived artifact, not an overwrite of the source conversation. Preserve unresolved actions, receipts, user constraints, and source ranges. Summary generation can incur model costs; users must choose the model, cost/frequency boundary, and stop control before automation is enabled.

**Acceptance focus:** interruption, cancellation, full storage, Keystore failure, model switch, and long tool histories. Restore the source session and do not lose or replay device side effects.

### 7. Event triggers and agent-maintained tools

Let people and agents manage triggers for battery, notifications, network, sensors, time, and future Android events. The human interface has higher authority. Agent CRUD is limited to event sources and tools already opened by the user. A trigger may define conditions, prompt, target session/agent, tool set, cooldown, debounce, concurrency, expiry, failure, and stop policy; actions still use the same tool protocol, authorization, runtime console, and receipts.

Start with read-only event observation and a “would trigger” preview, then add background wake and approved actions. Notifications, broadcasts, files, and model output remain data, not new grants. Idempotency protects against duplicate dispatch; each run is stoppable and traceable.

**Acceptance focus:** reboot and duplicate broadcasts, OEM background limits, offline/power-saving conditions, trigger version updates, pause/revoke races, permission revocation, unknown actions, and bounded retry.

### 8. External Tool Service and ecosystem integration

Add a third mode above Native and Gateway: AndroBody serves MCP/IPC tools to another Harness. The client receives only a user-authorized tool scope, not AndroBody's UID, upstream model keys, other apps' private data, or inherited Android permissions. Authentication, revocable tokens, client identity, per-tool authorization, concurrency/rate bounds, revocation, and audit come first.

Research the actual protocols used by AndroidHarness and Termux before selecting an adapter. External mode is a transport, not a replacement for dedicated tools or Native/Gateway ownership. Generic and specialized calls must share implementation and evidence.

## Interaction and validation constraints

- Use AndroidHarness's project/chat split, model selection, empty-state examples, tool progress, and recovery as the primary reference. Validate phone, tablet, and narrow/wide layouts on real viewports. Treat unreleased Glass experiments as research only.
- Follow ARC principles: concise language, progressive disclosure, direct manipulation, and truthful state. The runtime console is the detailed diagnostic surface; chat shows only what the active task needs.
- Record source review, host, emulator, physical-device, and human-experience evidence separately. A plan, API, permission switch, or model claim is not an actual call or independent observation.
- Preserve failure and success samples. If a result cannot be reproduced, mark it unknown and retain version/device scope plus request/trace/run references for regression.

## Related documents

- [Architecture](ARCHITECTURE.en.md)
- [Inspectable delegation contract](architecture/INSPECTABLE_DELEGATION.en.md)
- [Chinese roadmap](ROADMAP.md)

Per-device diagnostic records stay separate from the public roadmap. Public material must not include private configurations or raw user logs.
