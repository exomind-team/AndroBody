# Inspectable delegation contract

**Language** · [简体中文](INSPECTABLE_DELEGATION.md) · [English](INSPECTABLE_DELEGATION.en.md)

AndroBody treats execution as a chain of facts that people and agents can inspect. A tool name, model response, or system dispatch is not evidence that the intended effect occurred.

## Shared state

Each delegated action has stable capability, session, request/trace, and invocation identifiers where applicable. Summary, detailed inspection, and exported report views derive from the same records; they do not maintain independent status copies.

A record distinguishes:

- requested capability and declared scope;
- policy and Android permission checks;
- whether approval was required and received;
- whether execution was attempted and how far it progressed;
- system or target-app response;
- independent observations, when available;
- remaining uncertainty, failure reason, and recovery path.

## Result language

`Submitted` means an operation was handed to a system API. `Completed` requires evidence for the operation's stated completion boundary. `Unknown` means current evidence cannot decide the outcome. `Failed` identifies the observed failure stage. Cancellation prevents later work where possible; it does not claim to undo an earlier side effect.

Static inspection can identify a candidate API, component, or permission. It cannot establish that the current device can use it or that Android blocks it. A tested blocker requires an actual attempt and a recorded reason. Untested and unimplemented capabilities remain visible as such.

## Recovery and regression

Keep unresolved results inspectable without locking the conversation. Before continuing after an ambiguous device action, read the existing receipt or state; never repeat a non-idempotent action merely because its response was lost. Preserve session and task identity through retry, cancellation, compaction, and recovery.

Turn failures and surprising successes into bounded regression cases. Retain enough version, input, and evidence references to reproduce the decision, while keeping secrets and user content out of ordinary logs.

## Privacy and trust

Credentials, prompts, notifications, recordings, file content, and other-app data are not ordinary diagnostic metadata. External model transmission, local-file access, Android system permissions, and exported reports each have separate scopes and user decisions. Data received from tools or other apps is untrusted input and cannot grant authority.
