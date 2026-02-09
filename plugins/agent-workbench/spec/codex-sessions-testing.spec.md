---
name: Agent Threads Testing
description: Contract and UI test coverage for the Agent Threads tool window.
targets:
  - ../sessions/testSrc/CodexAppServerClientTest.kt
  - ../sessions/testSrc/CodexAppServerClientTestSupport.kt
  - ../sessions/testSrc/CodexTestAppServer.kt
  - ../sessions/testSrc/AgentSessionsToolWindowTest.kt
---

# Agent Threads Testing

Status: Draft
Date: 2026-02-08

## Scope
Validate Codex app-server thread listing and tool window UI states with a contract test suite that runs against mock and (optionally) real Codex backends.

## Contract Suite
- `CodexAppServerClientTest` is a parameterized contract test that executes against:
  - Mock app-server (`CodexTestAppServer`) using a synthetic config file.
  - Real `codex app-server` when the CLI is available (skipped otherwise).
- Both backends share the same invariant assertions:
  - Threads are sorted by `updatedAt` descending.
  - `archived` flags are consistent with the requested list.
- The mock backend additionally asserts exact thread IDs because the fixture is deterministic. The real backend uses only invariant assertions because thread data is user-specific and unstable.

[@test] ../sessions/testSrc/CodexAppServerClientTest.kt

## Tool Window UI Coverage
- Empty, loading, and error states render the expected copy.
- Active and archived sections render the right action labels.
- `New Thread` is available for closed project rows and clicking it does not invoke the project-open callback.
- Clicking `New Thread` invokes create-thread callback exactly once.
- Hovering `New Thread` does not change project-row layout metrics (height/content shift).

[@test] ../sessions/testSrc/AgentSessionsToolWindowTest.kt

## Integration Gating
- The real backend runs when the `codex` CLI is resolvable.
- `CODEX_BIN` can be used to point at a specific binary; otherwise PATH is used.

## Isolation
- The test creates a fresh `CODEX_HOME` directory in a temp location.
- A minimal `config.toml` is generated there for the real backend.
- `CODEX_HOME` is set only for the spawned `codex app-server` via per-process environment overrides.
- No global environment state is mutated.

## Running Locally
Use the sessions module as the test main module to avoid "No tests found":

```bash
./tests.cmd \
  -Dintellij.build.test.patterns=com.intellij.agent.workbench.codex.sessions.CodexAppServerClientTest \
  -Dintellij.build.test.main.module=intellij.agent.workbench.sessions
```

To point to a specific CLI binary:
```bash
export CODEX_BIN=/path/to/codex
```

Optional model override (defaults to `gpt-4o-mini`):
```bash
export CODEX_MODEL=gpt-4o-mini
```

Optional reasoning effort override (defaults to `low`):
```bash
export CODEX_REASONING_EFFORT=low
```

The real backend requires Codex CLI authentication to be available in the environment.
