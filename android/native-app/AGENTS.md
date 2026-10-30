# Native Android module

This module owns AndroBody's Kotlin/Compose application, native sessions, capability policy, Android adapters, local Gateway and runtime diagnostics.

- Keep Native, Gateway and the planned External Tool Service as distinct ownership modes; share implementation and receipts where their contracts match.
- System permissions, tool policy and observed effects are separate state. Tools return the actual phase and uncertainty rather than inferring success from dispatch.
- Keep user-visible details contextual. The runtime console is the deeper inspection surface; ordinary logs must not include secrets or content by default.
- Preserve the capability and evidence contract in `docs/ROADMAP.md` and `docs/ARCHITECTURE.md`.
- Verification builds use a separate package identity and debug signing. Do not clear production data or grant Android permissions from a test runner.
