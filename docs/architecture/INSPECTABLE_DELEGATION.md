# Inspectable delegation contract

**Language** · [简体中文](INSPECTABLE_DELEGATION.md) · [English](INSPECTABLE_DELEGATION.en.md)

AndroBody treats execution as a chain of facts that users and agents can inspect. A tool name, model response or system dispatch is not a substitute for evidence about what actually happened.

## Shared state

Every delegated action has stable capability, session, request/trace and invocation identifiers where applicable. The summary view, detailed inspection and exported report derive from the same records; they must not maintain independent status copies.

A record distinguishes:

- the requested capability and its declared scope;
- policy and Android permission checks;
- whether approval was required and received;
- whether execution was attempted and how far it progressed;
- the system or target application response;
- independent observations, if available;
- remaining uncertainty, failure reason and recovery path.

## Result language

`Submitted` means an operation was handed to a system API. `Completed` requires evidence for the operation's stated completion boundary. `Unknown` means available evidence cannot decide the outcome. `Failed` identifies the observed failure stage. Cancellation prevents later work where possible; it does not claim to undo a side effect that already happened.

Static inspection can identify a possible API, component or permission. It cannot establish that the current device can use it or that Android blocks it. A tested blocker requires an actual attempt and a recorded reason. Untested and unimplemented capabilities remain visible as such.

## Recovery and regression

Keep unresolved results available for inspection without locking the conversation. Before continuing after an ambiguous device action, read the existing receipt or state; never repeat a non-idempotent action merely because its response was lost. Preserve the original session and task identity through retries, cancellation, compaction and recovery.

Turn failures and surprising successes into bounded regression cases. Store enough versions, inputs and evidence references to reproduce the decision while keeping secrets and user content out of ordinary logs.

## Privacy and trust

Credentials, prompts, notification bodies, recordings, file content and other-app data are not ordinary diagnostic metadata. External model transmission, local file access, Android system permissions and exported reports each have their own scope and user decision. Data received from tools or other apps is untrusted input and cannot grant new authority.
